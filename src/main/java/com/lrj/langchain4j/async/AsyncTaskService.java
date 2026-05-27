package com.lrj.langchain4j.async;

import com.lrj.langchain4j.ai.multiagent.MultiAgentService;
import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import com.lrj.langchain4j.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * 异步任务编排：投递 → 状态查询 → 取消。
 *
 * <p>线程模型：复用 {@code multiAgentExecutor}（{@code MdcCopyingTaskDecorator} 已经透传
 * MDC + {@link TenantContext}），所以 worker 子线程发起的 LLM 调用、audit 都能正确归属租户。
 *
 * <p>取消支持：留 {@code Map<taskId, Future>}，{@code cancel} 走 {@code Future.cancel(true)}
 * 中断 worker 线程。但 multi-agent 内部还会 fan-out 到更多线程，那些 sub-future 不在本 map 里 ——
 * MVP 只能 best-effort 取消，sub-task 可能已经在跑。这是已知限制，要彻底支持要把 MultiAgentService
 * 改成 cancellation-aware（每个子任务前 check Thread.interrupted()）。
 */
@Service
public class AsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final TaskStore store;
    private final Executor executor;
    private final MultiAgentService multiAgent;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;

    private final ConcurrentMap<String, CompletableFuture<?>> futures = new ConcurrentHashMap<>();

    public AsyncTaskService(TaskStore store,
                            @Qualifier("multiAgentExecutor") Executor executor,
                            MultiAgentService multiAgent,
                            AuditLogger audit,
                            ApplicationEventPublisher events) {
        this.store = store;
        this.executor = executor;
        this.multiAgent = multiAgent;
        this.audit = audit;
        this.events = events;
    }

    public AsyncTask submitMultiAgent(String message, String webhookUrl) {
        TenantContext.Tenant t = TenantContext.current();
        String taskId = UUID.randomUUID().toString();
        AsyncTask task = new AsyncTask(
                taskId, t.tenantId(), t.userId(),
                TaskKind.MULTI_AGENT, TaskStatus.PENDING,
                Map.of("message", message),
                null, null,
                webhookUrl,
                Instant.now(), Instant.now(), null);
        store.put(task);
        events.publishEvent(new TaskEvent(task));
        audit.record(AuditEventType.ASYNC_TASK_SUBMITTED, Map.of(
                "taskId", taskId, "kind", TaskKind.MULTI_AGENT.name(),
                "webhook", webhookUrl != null));

        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
            updateAndFire(taskId, cur -> cur.withStatus(TaskStatus.RUNNING, null, null));
            try {
                MultiAgentService.Run run = multiAgent.run(message);
                updateAndFire(taskId, cur ->
                        cur.status() == TaskStatus.CANCELLED
                                ? cur                                    // 已被外部取消，不覆盖
                                : cur.withStatus(TaskStatus.SUCCEEDED, run, null));
                audit.record(AuditEventType.ASYNC_TASK_FINISHED, Map.of(
                        "taskId", taskId, "status", "SUCCEEDED"));
            } catch (Throwable ex) {
                String msg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                updateAndFire(taskId, cur -> cur.withStatus(TaskStatus.FAILED, null, msg));
                audit.record(AuditEventType.ASYNC_TASK_FINISHED, Map.of(
                        "taskId", taskId, "status", "FAILED", "error", msg));
                log.warn("async task {} failed", taskId, ex);
            } finally {
                futures.remove(taskId);
            }
        }, executor);

        futures.put(taskId, f);
        return task;
    }

    /** 兼容老调用方（没有 webhook 的场景）。 */
    public AsyncTask submitMultiAgent(String message) {
        return submitMultiAgent(message, null);
    }

    /**
     * CAS 更新 store 后 fire event。把 fire 跟 update 绑在一起避免漏发 ——
     * 更新成功就一定推一次（即便是 no-op 终态守卫返回的同一个 record，订阅方自己幂等）。
     */
    private void updateAndFire(String taskId, java.util.function.UnaryOperator<AsyncTask> updater) {
        store.update(taskId, updater).ifPresent(t -> events.publishEvent(new TaskEvent(t)));
    }

    /** Per-tenant 校验 —— 只能看自己的 task，跨租户返回 empty（不区分 "不存在" vs "无权访问"，防枚举）。 */
    public Optional<AsyncTask> get(String taskId) {
        String tenantId = TenantContext.current().tenantId();
        return store.get(taskId).filter(t -> t.tenantId().equals(tenantId));
    }

    public List<AsyncTask> listMine() {
        return store.listByTenant(TenantContext.current().tenantId());
    }

    public boolean cancel(String taskId) {
        Optional<AsyncTask> task = get(taskId);
        if (task.isEmpty()) return false;
        if (task.get().status().isTerminal()) return false;

        CompletableFuture<?> f = futures.get(taskId);
        if (f != null) f.cancel(true);                                  // best-effort interrupt
        updateAndFire(taskId, cur -> cur.status().isTerminal()
                ? cur
                : cur.withStatus(TaskStatus.CANCELLED, null, "cancelled by user"));
        audit.record(AuditEventType.ASYNC_TASK_CANCELLED, Map.of("taskId", taskId));
        return true;
    }
}
