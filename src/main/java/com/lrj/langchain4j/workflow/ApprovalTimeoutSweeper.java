package com.lrj.langchain4j.workflow;

import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 审批超时扫描器（roadmap F 节 🔴 一档 #1）。定时扫挂起超过 {@code app.workflow.approval-timeout}
 * 的审批 UserTask，调 {@link WorkflowService#expireTask} 自动驳回，避免审批人漏看导致流程永久挂起、
 * 用户永远收不到回复。
 *
 * <p><b>为什么用 {@code @Scheduled} 而不是 BPMN boundary timer</b>：Flowable timer 必须
 * {@code asyncExecutorActivate=true} 才会触发，而 {@link WorkflowConfig} 刻意关掉 async executor
 * 规避坑 2（async 线程 ThreadLocal 真空）。走调度线程则保持 async executor 关、零线程模型改动、
 * 现有三分支零回归。代价：轮询粒度 + 超时逻辑在 Java 不在 BPMN。
 *
 * <p>本类整体 {@code @ConditionalOnProperty(app.workflow.enabled)}，默认关时不装配（主类已
 * {@code @EnableScheduling}，见 {@code LangChain4jApplication}）。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class ApprovalTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutSweeper.class);

    private final TaskService taskService;
    private final WorkflowService workflowService;
    private final WorkflowProperties props;

    public ApprovalTimeoutSweeper(TaskService workflowTaskService,
                                  WorkflowService workflowService,
                                  WorkflowProperties props) {
        this.taskService = workflowTaskService;
        this.workflowService = workflowService;
        this.props = props;
    }

    /**
     * 扫描并驳回超时审批。跨所有租户（调度线程无租户上下文）；租户隔离在
     * {@link WorkflowService#expireTask} 内从流程变量重建。单条失败不影响其余
     * （照抄 {@code TaskStore.cleanup()} 的健壮性风格）。
     */
    @Scheduled(fixedDelayString = "${app.workflow.timeout-sweep-interval-ms:60000}", initialDelay = 60_000)
    public void sweep() {
        Date cutoff = cutoff(Instant.now(), props.getApprovalTimeout());
        List<Task> overdue = taskService.createTaskQuery()
                .taskCreatedBefore(cutoff)
                .active()
                .list();
        if (overdue.isEmpty()) {
            return;
        }
        int expired = 0;
        for (Task t : overdue) {
            try {
                workflowService.expireTask(t.getId());
                expired++;
            } catch (Exception e) {
                // 单条失败（含与人工 complete 的竞态）不阻断扫描，下一轮还会再扫到
                log.warn("approval timeout sweep: expire task {} failed: {}", t.getId(), e.toString());
            }
        }
        log.info("approval timeout sweep: scanned {} overdue, auto-rejected {} (timeout={})",
                overdue.size(), expired, props.getApprovalTimeout());
    }

    /** 超时分界点 = now - timeout。抽 static 纯函数便于单测。 */
    static Date cutoff(Instant now, Duration timeout) {
        return Date.from(now.minus(timeout));
    }
}
