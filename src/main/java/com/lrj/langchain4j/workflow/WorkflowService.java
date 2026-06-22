package com.lrj.langchain4j.workflow;

import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import com.lrj.langchain4j.security.ApiKeyAuthFilter;
import com.lrj.langchain4j.security.TenantContext;
import com.lrj.langchain4j.observability.TraceIdFilter;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 退款审批工作流的业务封装：启流程 / 查待办 / 完成审批 / 查实例，挡在 Flowable
 * {@link RuntimeService} + {@link TaskService} + {@link HistoryService} 之上。
 *
 * <p><b>租户隔离（坑 3）</b>：每个流程实例都把发起人的 {@code tenantId} 写成流程变量。
 * 待办列表 / 完成审批 / 查实例都用 {@code processVariableValueEquals("tenantId", 当前租户)}
 * 过滤，租户 B 拿不到、也无法 complete 租户 A 的任务。<em>不</em>依赖 Flowable 原生 start-tenant
 * 查找——classpath 是 tenant-less 部署，带 tenant 启动需 fallback 配置，流程变量过滤更简单且同样严格。
 *
 * <p>v1 所有 ServiceTask 同步执行（async executor 关），故 {@code start()} 返回时流程要么已到
 * End（{@code COMPLETED}，reply 已生成），要么停在 UserTask（{@code WAITING_APPROVAL}，待人工）。
 */
@Service
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    /** 对应 refund-approval.bpmn20.xml 里 process 的 id。 */
    private static final String PROCESS_KEY = "refundApproval";

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_WAITING = "WAITING_APPROVAL";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final AuditLogger audit;
    private final WorkflowProperties props;
    private final WorkflowReplyStore replyStore;
    private final WorkflowMetrics metrics;
    private final WorkflowOutbox outbox;
    private final org.springframework.context.ApplicationEventPublisher events;

    public WorkflowService(RuntimeService workflowRuntimeService,
                           TaskService workflowTaskService,
                           HistoryService workflowHistoryService,
                           AuditLogger audit,
                           WorkflowProperties props,
                           WorkflowReplyStore replyStore,
                           WorkflowMetrics metrics,
                           WorkflowOutbox outbox,
                           org.springframework.context.ApplicationEventPublisher events) {
        this.runtimeService = workflowRuntimeService;
        this.taskService = workflowTaskService;
        this.historyService = workflowHistoryService;
        this.audit = audit;
        this.props = props;
        this.replyStore = replyStore;
        this.metrics = metrics;
        this.outbox = outbox;
        this.events = events;
    }

    /**
     * 终态处理收口（#8 outbox 入队 + 渠道回推事件）。三个终态点（自动受理 / 人工 complete / 超时驳回）
     * 各调一次。{@code chatId} 缺省从流程变量读。
     */
    private void onTerminal(String instanceId, String tenantId, String chatId, String outcome) {
        enqueuePush(instanceId, tenantId);
        String cid = chatId != null ? chatId : str(readVariable(instanceId, "chatId"));
        events.publishEvent(new WorkflowTerminalEvent(
                instanceId, tenantId, cid, outcome, replyStore.find(instanceId)));
    }

    /**
     * 发起退款流程。低风险自动受理（直接 COMPLETED），高风险挂起等审批（WAITING_APPROVAL）。
     *
     * <p><b>幂等（#2）</b>：传了 {@code dedupeId}（渠道消息 id）就用稳定 businessKey
     * {@code tenant:chatId:dedupeId} 去重——重复 start 同一诉求只起一个流程（防飞书 ~5s ack 超时重推
     * 起 N 个流程 + N 个审批任务）。不传 dedupeId 则用随机 UUID businessKey（仅追溯、不去重，
     * 避免按 message 文本误并两次合法的相同提问；渠道接入后再传真 id）。
     *
     * <p><b>残留竞态</b>：查-建非原子，两个并发同 dedupeId 仍可能都漏检→都建。v1 接受（渠道重推秒级近似串行，
     * query 预检挡住绝大多数）。强幂等升级点：Redis {@code SETNX}（项目已用 {@code RedisChatMemoryStore}）
     * 或 dedup 表唯一索引。
     */
    public StartResult start(String chatId, String message, String dedupeId, String webhookUrl) {
        TenantContext.Tenant t = TenantContext.current();
        String cid = chatId == null ? "default" : chatId;
        String businessKey = buildBusinessKey(t.tenantId(), cid, dedupeId);

        // 仅当传了 dedupeId 才查重（无 dedupeId 时 businessKey 是随机 UUID，永远不命中）
        if (dedupeId != null && !dedupeId.isBlank()) {
            ProcessInstance existing = runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey)
                    .variableValueEquals("tenantId", t.tenantId())  // 租户内去重，跨租户互不影响
                    .singleResult();
            if (existing != null) {
                log.info("workflow start deduplicated: businessKey={} instanceId={}", businessKey, existing.getId());
                return describeExisting(existing.getId(), true);
            }
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("tenantId", t.tenantId());
        vars.put("userId", t.userId());
        vars.put("chatId", cid);
        vars.put("message", message == null ? "" : message);
        // #8：发起方传了回推地址就存成流程变量，终态时入 outbox 可靠投递
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            vars.put("webhookUrl", webhookUrl.trim());
        }
        // 把当前请求的 traceId 存成流程变量，超时 sweep 时取回放进 MDC → 日志跨事件串联（计划 2.5）
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            vars.put("startTraceId", traceId);
        }

        ProcessInstance pi = runtimeService.startProcessInstanceByKey(PROCESS_KEY, businessKey, vars);
        String instanceId = pi.getId();
        String priority = str(readVariable(instanceId, "priority"));
        audit.record(AuditEventType.WORKFLOW_STARTED, Map.of(
                "instanceId", instanceId, "chatId", cid, "priority", nz(priority)));
        metrics.recordStarted(priority);

        if (pi.isEnded()) {
            audit.record(AuditEventType.WORKFLOW_COMPLETED, Map.of("instanceId", instanceId, "approval", "auto"));
            metrics.recordCompleted("auto");
            onTerminal(instanceId, t.tenantId(), cid, "auto");
            return new StartResult(instanceId, STATUS_COMPLETED, replyStore.find(instanceId), null, priority, false);
        }

        Task task = taskService.createTaskQuery().processInstanceId(instanceId).active().singleResult();
        String taskId = task == null ? null : task.getId();
        audit.record(AuditEventType.APPROVAL_REQUESTED, Map.of(
                "instanceId", instanceId, "taskId", nz(taskId), "priority", nz(priority)));
        return new StartResult(instanceId, STATUS_WAITING, null, taskId, priority, false);
    }

    /**
     * #8 终态入队：实例若带 {@code webhookUrl} 流程变量，把"待投递"落 {@link WorkflowOutbox}，
     * 由 {@code WorkflowOutboxDispatcher} 可靠重投。无 webhookUrl 则不入队（客户端轮询 status 端点，行为同旧版）。
     * best-effort：入队失败只告警不影响主流程（reply 已持久，客户端仍可轮询）。
     */
    private void enqueuePush(String instanceId, String tenantId) {
        String url = str(readVariable(instanceId, "webhookUrl"));
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            outbox.enqueue(instanceId, tenantId, url, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("outbox enqueue 失败 instanceId={}：{}", instanceId, e.toString());
        }
    }

    /**
     * 构造流程 businessKey。传了 {@code dedupeId} → 稳定可去重的 {@code tenant:chatId:dedupeId}；
     * 否则随机 UUID 后缀（仅追溯、不去重）。抽成 static 纯函数便于单测。
     */
    static String buildBusinessKey(String tenantId, String chatId, String dedupeId) {
        if (dedupeId != null && !dedupeId.isBlank()) {
            return tenantId + ":" + chatId + ":" + dedupeId.trim();
        }
        return tenantId + ":" + chatId + ":" + UUID.randomUUID();
    }

    /** 把一个既有实例（去重命中时）描述成 StartResult：已结束回 COMPLETED+reply，在跑回 WAITING+taskId。 */
    private StartResult describeExisting(String instanceId, boolean deduplicated) {
        boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult() != null;
        String priority = str(readVariable(instanceId, "priority"));
        if (!running) {
            return new StartResult(instanceId, STATUS_COMPLETED, replyStore.find(instanceId), null, priority, deduplicated);
        }
        Task task = taskService.createTaskQuery().processInstanceId(instanceId).active().singleResult();
        String taskId = task == null ? null : task.getId();
        return new StartResult(instanceId, STATUS_WAITING, null, taskId, priority, deduplicated);
    }

    /** 本租户待审 UserTask 列表。 */
    public List<TaskView> listTasks() {
        String tenant = TenantContext.current().tenantId();
        List<Task> tasks = taskService.createTaskQuery()
                .processVariableValueEquals("tenantId", tenant)
                .active()
                .orderByTaskCreateTime().desc()
                .list();
        List<TaskView> views = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            String instanceId = task.getProcessInstanceId();
            views.add(new TaskView(
                    task.getId(),
                    task.getName(),
                    instanceId,
                    str(readVariable(instanceId, "priority")),
                    str(readVariable(instanceId, "summary")),
                    task.getAssignee()));
        }
        return views;
    }

    /**
     * 认领任务（#7 任务分配粒度）：把任务 assignee 设为当前用户，避免多人审同一单。
     * 已被他人认领 → 409（友好冲突，不是 500）。返回认领后的任务视图。
     */
    public TaskView claim(String taskId) {
        TenantContext.Tenant t = TenantContext.current();
        Task task = activeTenantTask(taskId, t.tenantId());
        String assignee = task.getAssignee();
        if (assignee != null && !assignee.isBlank() && !assignee.equals(t.userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already claimed by " + assignee);
        }
        try {
            taskService.claim(taskId, t.userId());
        } catch (FlowableObjectNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already handled: " + taskId);
        }
        String instanceId = task.getProcessInstanceId();
        return new TaskView(taskId, task.getName(), instanceId,
                str(readVariable(instanceId, "priority")), str(readVariable(instanceId, "summary")), t.userId());
    }

    /** 取消认领（#7）：把任务放回待领池，供同租户其他 approver 接手。 */
    public void unclaim(String taskId) {
        TenantContext.Tenant t = TenantContext.current();
        activeTenantTask(taskId, t.tenantId()); // 租户校验 + 存在性
        try {
            taskService.unclaim(taskId);
        } catch (FlowableObjectNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already handled: " + taskId);
        }
    }

    /** 取本租户下、仍 active 的任务；不存在/已处理/跨租户一律 404（不泄露跨租户任务存在）。 */
    private Task activeTenantTask(String taskId, String tenant) {
        Task task = taskService.createTaskQuery()
                .taskId(taskId)
                .processVariableValueEquals("tenantId", tenant)
                .active()
                .singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found: " + taskId);
        }
        return task;
    }

    /** 完成审批：approved=true 走 resolve，false 走 reject（均同步执行），返回最终 reply。 */
    public CompleteResult complete(String taskId, boolean approved, String comment) {
        String tenant = TenantContext.current().tenantId();
        Task task = activeTenantTask(taskId, tenant);
        String instanceId = task.getProcessInstanceId();
        long approvalMs = approvalDurationMs(task);

        Map<String, Object> vars = new HashMap<>();
        vars.put("approved", approved);
        vars.put("comment", comment == null ? "" : comment);
        try {
            taskService.complete(taskId, vars);
        } catch (FlowableObjectNotFoundException e) {
            // #7 并发双重审批：预检与 complete 之间另一审批人/超时 sweeper 已处理掉 → 友好 409，不是 500
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already handled by another approver: " + taskId);
        }

        audit.record(approved ? AuditEventType.APPROVAL_GRANTED : AuditEventType.APPROVAL_REJECTED,
                Map.of("instanceId", instanceId, "taskId", taskId));
        audit.record(AuditEventType.WORKFLOW_COMPLETED, Map.of(
                "instanceId", instanceId, "approval", approved ? "granted" : "rejected"));
        metrics.recordApprovalDuration(approvalMs);
        metrics.recordCompleted(approved ? "granted" : "rejected");
        onTerminal(instanceId, tenant, null, approved ? "granted" : "rejected");
        log.info("workflow complete: instanceId={} approved={}", instanceId, approved);

        return new CompleteResult(instanceId, STATUS_COMPLETED, replyStore.find(instanceId), approved);
    }

    /** UserTask 创建到现在的耗时（毫秒），创建时间缺失记 0。 */
    private static long approvalDurationMs(Task task) {
        return task.getCreateTime() == null ? 0L : System.currentTimeMillis() - task.getCreateTime().getTime();
    }

    /**
     * 审批超时自动驳回（#1）。由 {@link ApprovalTimeoutSweeper} 在 Spring 调度线程调用——该线程不过
     * 过滤器链，{@link TenantContext} 和日志 MDC 都是空的。故这里：
     * <ul>
     *   <li>从流程变量 {@code tenantId} 重建 {@link TenantContext}（审计归属正确）；</li>
     *   <li>铺 MDC 三件套（{@code traceId}/{@code tenantId}/{@code userId}），日志才不是 {@code [-] [-/-]}。
     *       traceId 优先取流程变量 {@code startTraceId}（start 时存的请求 traceId）→ 一个 id 串起
     *       「start」与「24h 后的超时驳回」；缺失则现造 8 位（仅内部串联）。</li>
     * </ul>
     * 走既有 reject 路径（{@code approved=false}），reply 由 {@code ServiceTaskDelegates.rejectionMessage} 生成。
     *
     * <p><b>与人工 complete 的竞态</b>：若超时同时审批人正点 complete，先到者赢；后到的 task 已不 active，
     * {@code active().singleResult()==null} 直接跳过（这是 #7「并发 complete 500」的局部预防）。
     */
    public void expireTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null) {
            return; // 已被人工处理 / 不存在 → 幂等跳过
        }
        String instanceId = task.getProcessInstanceId();
        String tenantId = str(readVariable(instanceId, "tenantId"));
        String startTraceId = str(readVariable(instanceId, "startTraceId"));
        long approvalMs = approvalDurationMs(task);

        TenantContext.Tenant prevTenant = TenantContext.captureRaw();
        String prevTrace = MDC.get(TraceIdFilter.MDC_KEY);
        String prevTenantMdc = MDC.get(ApiKeyAuthFilter.MDC_TENANT);
        String prevUserMdc = MDC.get(ApiKeyAuthFilter.MDC_USER);
        try {
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.set(new TenantContext.Tenant(tenantId, "system-timeout", Set.of()));
            }
            MDC.put(TraceIdFilter.MDC_KEY,
                    (startTraceId != null && !startTraceId.isBlank())
                            ? startTraceId
                            : UUID.randomUUID().toString().substring(0, 8));
            if (tenantId != null) MDC.put(ApiKeyAuthFilter.MDC_TENANT, tenantId);
            MDC.put(ApiKeyAuthFilter.MDC_USER, "system-timeout");

            Map<String, Object> vars = new HashMap<>();
            vars.put("approved", false);
            vars.put("comment", "审批超时自动驳回（超过 " + props.getApprovalTimeout() + " 无人处理）");
            try {
                taskService.complete(taskId, vars); // → reject ServiceTask 同步跑，写超时驳回 reply
            } catch (FlowableObjectNotFoundException e) {
                // 与人工 complete 竞态：审批人在预检后抢先完成 → 已无此 task，幂等跳过
                log.info("workflow expire skipped (already handled): taskId={}", taskId);
                return;
            }

            audit.record(AuditEventType.APPROVAL_TIMEOUT, Map.of("instanceId", instanceId, "taskId", taskId));
            audit.record(AuditEventType.WORKFLOW_COMPLETED, Map.of("instanceId", instanceId, "approval", "timeout"));
            metrics.recordTimeout();
            metrics.recordApprovalDuration(approvalMs);
            metrics.recordCompleted("timeout");
            onTerminal(instanceId, tenantId, null, "timeout");
            log.info("workflow approval timeout auto-rejected: instanceId={} taskId={}", instanceId, taskId);
        } finally {
            if (prevTenant != null) TenantContext.set(prevTenant); else TenantContext.clear();
            restoreMdc(TraceIdFilter.MDC_KEY, prevTrace);
            restoreMdc(ApiKeyAuthFilter.MDC_TENANT, prevTenantMdc);
            restoreMdc(ApiKeyAuthFilter.MDC_USER, prevUserMdc);
        }
    }

    private static void restoreMdc(String key, String prev) {
        if (prev != null) MDC.put(key, prev); else MDC.remove(key);
    }

    /** 查实例状态 + reply。跨租户访问按 404 处理。 */
    public InstanceView getInstance(String instanceId) {
        String owner = str(readVariable(instanceId, "tenantId"));
        String tenant = TenantContext.current().tenantId();
        if (owner == null || !owner.equals(tenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found: " + instanceId);
        }
        boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult() != null;
        String status = running ? STATUS_WAITING : STATUS_COMPLETED;
        return new InstanceView(instanceId, status, replyStore.find(instanceId));
    }

    /**
     * PII 合规删除（#10）：清除本租户某 {@code chatId} 下的所有工作流持久化数据——运行中实例（强制删）、
     * 历史实例（{@code ACT_HI_*}）、{@code WF_REPLY}、{@code WF_OUTBOX}。覆盖个保法"删除我的数据"诉求；
     * {@code message}/{@code summary}/{@code reply} 这些可能含 PII 的字段一并清掉。
     *
     * <p>按流程变量 {@code tenantId}+{@code chatId} 定位，跨租户删不到（租户隔离同 {@link #listTasks}）。
     * 返回删除的实例数。审计 {@link AuditEventType#WORKFLOW_DATA_PURGED}。
     */
    public int purge(String chatId) {
        String tenant = TenantContext.current().tenantId();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();

        // 运行中实例：先强制删（带走 ACT_RU_*）
        for (ProcessInstance pi : runtimeService.createProcessInstanceQuery()
                .variableValueEquals("tenantId", tenant)
                .variableValueEquals("chatId", chatId)
                .list()) {
            ids.add(pi.getId());
            try {
                runtimeService.deleteProcessInstance(pi.getId(), "PII purge");
            } catch (Exception e) {
                log.warn("purge: 删运行中实例 {} 失败：{}", pi.getId(), e.toString());
            }
        }
        // 历史实例：含已结束的，删 ACT_HI_*
        for (HistoricProcessInstance hi : historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals("tenantId", tenant)
                .variableValueEquals("chatId", chatId)
                .list()) {
            ids.add(hi.getId());
            try {
                historyService.deleteHistoricProcessInstance(hi.getId());
            } catch (Exception e) {
                log.warn("purge: 删历史实例 {} 失败：{}", hi.getId(), e.toString());
            }
        }
        // 业务表：reply + outbox
        for (String id : ids) {
            replyStore.delete(id);
            outbox.delete(id);
        }
        audit.record(AuditEventType.WORKFLOW_DATA_PURGED, Map.of(
                "chatId", nz(chatId), "instances", ids.size()));
        log.info("workflow purge: tenant={} chatId={} 清除实例数={}", tenant, chatId, ids.size());
        return ids.size();
    }

    /**
     * 读流程变量：实例还在跑就从 runtime 取；已结束则从 history 取（默认 history level=audit
     * 已记录变量）。COMPLETED 实例的 priority / summary 仍能回读。
     *
     * <p>注意 {@code reply} <b>不</b>走这里——它是长文本，已挪到 {@link WorkflowReplyStore}（#5），
     * 由 {@code replyStore.find(instanceId)} 取回，不再灌 {@code ACT_HI_VARINST}。
     */
    private Object readVariable(String instanceId, String name) {
        boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult() != null;
        if (running) {
            return runtimeService.getVariable(instanceId, name);
        }
        HistoricVariableInstance v = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(instanceId).variableName(name).singleResult();
        return v == null ? null : v.getValue();
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static String nz(String v) { return v == null ? "" : v; }

    public record StartResult(String instanceId, String status, String reply, String taskId, String priority,
                              boolean deduplicated) {}

    public record TaskView(String taskId, String name, String instanceId, String priority, String summary,
                           String assignee) {}

    public record CompleteResult(String instanceId, String status, String reply, boolean approved) {}

    public record InstanceView(String instanceId, String status, String reply) {}
}
