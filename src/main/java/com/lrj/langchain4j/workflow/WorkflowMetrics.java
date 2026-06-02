package com.lrj.langchain4j.workflow;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.flowable.engine.TaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 工作流维度的 Micrometer 指标（#9 工作流可观测性）。现有 observability 只覆盖 LLM 调用
 * （{@code gen_ai.client.*}），看不到"挂起多少审批 / 审批多久 / 超时多少 / 各终态占比"这些
 * 流程态运营指标。本类把它们补上，与 LLM 指标同走 {@code /actuator/prometheus}。
 *
 * <ul>
 *   <li>{@code workflow.tasks.pending}（gauge）—— 实时挂起待审 UserTask 数，scrape 时查库（低基数、可接受）；</li>
 *   <li>{@code workflow.started}（counter, tag={@code priority}）—— 发起量按优先级拆；</li>
 *   <li>{@code workflow.completed}（counter, tag={@code outcome}=auto|granted|rejected|timeout）—— 终态分支占比；</li>
 *   <li>{@code workflow.approval.duration}（timer）—— UserTask 从创建到完成的审批耗时（含超时驳回那条尾巴）；</li>
 *   <li>{@code workflow.approval.timeout}（counter）—— 超时自动驳回次数（SLA 破线信号）。</li>
 * </ul>
 *
 * <p>整体 {@code @ConditionalOnProperty(app.workflow.enabled)}，关闭时不装配（{@code MeterRegistry}
 * 由 actuator 始终提供）。{@link WorkflowService} 在各生命周期点调本类打点。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowMetrics {

    private final MeterRegistry registry;

    public WorkflowMetrics(MeterRegistry registry, TaskService workflowTaskService) {
        this.registry = registry;
        Gauge.builder("workflow.tasks.pending", workflowTaskService,
                        ts -> ts.createTaskQuery().active().count())
                .description("当前挂起待审的 UserTask 数")
                .register(registry);
    }

    public void recordStarted(String priority) {
        registry.counter("workflow.started", "priority", nz(priority)).increment();
    }

    /** outcome ∈ {auto, granted, rejected, timeout}。 */
    public void recordCompleted(String outcome) {
        registry.counter("workflow.completed", "outcome", nz(outcome)).increment();
    }

    /** UserTask 从创建到完成的审批耗时（毫秒）。负值（时钟漂移）夹到 0。 */
    public void recordApprovalDuration(long millis) {
        registry.timer("workflow.approval.duration").record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    public void recordTimeout() {
        registry.counter("workflow.approval.timeout").increment();
    }

    private static String nz(String v) {
        return (v == null || v.isBlank()) ? "unknown" : v;
    }
}
