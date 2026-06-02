package com.lrj.langchain4j.workflow;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.ai.extract.Extractor;
import com.lrj.langchain4j.ai.extract.Ticket;
import com.lrj.langchain4j.audit.AuditEventType;
import com.lrj.langchain4j.audit.AuditLogger;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.security.TenantContext;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * BPMN {@code ServiceTask} 的实现，被 {@code refund-approval.bpmn20.xml} 用
 * {@code flowable:expression="${serviceTaskDelegates.xxx(execution)}"} 调用（bean 名 = 类名首字母小写）。
 *
 * <p>三个方法对应流程里的三个 ServiceTask：
 * <ul>
 *   <li>{@link #assess} —— 抽工单（{@link Extractor}），把 priority/category/summary 写回流程变量，
 *       供后续排他网关判断是否需要人工审批。</li>
 *   <li>{@link #resolve} —— 生成给用户的答复（{@link Assistant}），写 {@code reply} 到 {@link WorkflowReplyStore}。
 *       低优先级自动通过、或人工批准后都走这里。</li>
 *   <li>{@link #reject} —— 驳回话术（含审批意见），写 reply。纯字符串拼装，不调模型。</li>
 * </ul>
 *
 * <p><b>坑 2 防御</b>：v1 这些方法都在触发线程同步执行（用户/审批人请求线程，已过过滤器链，
 * {@code TenantContext} 有值）。即便如此，每个方法仍从流程变量 {@code tenantId} 防御性重设
 * {@code TenantContext}（{@link #withTenant}）。将来真把 ServiceTask 切到 Flowable async executor 时业务零改动。
 *
 * <p><b>#3 事务边界 + 失败补偿</b>：{@code complete()} 调 {@code taskService.complete()} 时，会在<em>同一个
 * Flowable 事务</em>里同步跑下游 {@code resolve}/{@code reject}（async executor 关）。若 LLM 直接抛异常，
 * 整个事务回滚 → 已记录的<b>人工审批决定一并丢失</b>、任务退回 active、审批人吃 500、被迫重新审批。
 * 为此 {@link #assess}/{@link #resolve} 的 LLM 调用走 {@link #withRetry}：<b>有界重试后仍失败则降级兜底、
 * 绝不向 Flowable 抛异常</b>。于是事务边界变成「人工决定 + 一定有终态 reply」原子提交；LLM 是事务内
 * best-effort，失败降级不中止。降级落地时审计 {@link AuditEventType#REPLY_DEGRADED} 留痕供补做。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class ServiceTaskDelegates {

    private static final Logger log = LoggerFactory.getLogger(ServiceTaskDelegates.class);

    private final Extractor extractor;
    private final Assistant assistant;
    private final ResolvedAssistantStyle style;
    private final WorkflowReplyStore replyStore;
    private final AuditLogger audit;
    private final WorkflowProperties props;

    public ServiceTaskDelegates(Extractor extractor, Assistant assistant, ResolvedAssistantStyle style,
                                WorkflowReplyStore replyStore, AuditLogger audit, WorkflowProperties props) {
        this.extractor = extractor;
        this.assistant = assistant;
        this.style = style;
        this.replyStore = replyStore;
        this.audit = audit;
        this.props = props;
    }

    /** 抽工单 → 写 priority / category / summary 流程变量。 */
    public void assess(DelegateExecution execution) {
        withTenant(execution, () -> {
            String message = str(execution.getVariable("message"));
            String instanceId = execution.getProcessInstanceId();
            // #3：抽取失败不让整条流程炸；降级成一张「强制转人工」的兜底工单（priority=HIGH）——
            // 抽不出风险时宁可多一道人工审，绝不默认 LOW 把潜在高风险退款自动放过。
            Attempted<Ticket> a = withRetry("assess", () -> extractor.extractTicket(message),
                    () -> degradedTicket(message));
            Ticket ticket = a.value();
            if (a.degraded()) {
                audit.record(AuditEventType.REPLY_DEGRADED, Map.of(
                        "instanceId", nz(instanceId), "stage", "assess", "fallbackPriority", ticket.priority().name()));
            }
            execution.setVariable("priority", ticket.priority().name());
            execution.setVariable("category", ticket.category());
            execution.setVariable("summary", ticket.summary());
            log.info("workflow assess: priority={} category={} degraded={}", ticket.priority(), ticket.category(), a.degraded());
            return null;
        });
    }

    /** 生成通过/受理答复 → 写 reply 到 {@link WorkflowReplyStore}。 */
    public void resolve(DelegateExecution execution) {
        withTenant(execution, () -> {
            String message = str(execution.getVariable("message"));
            String chatId = str(execution.getVariable("chatId"));
            String instanceId = execution.getProcessInstanceId();
            String scopedChatId = TenantContext.current().tenantId() + ":" + chatId;
            String userMessage = """
                    你是客服助手。用户提交的退款相关请求现已可以处理（低风险自动受理或人工已批准）。
                    请用中文写一段简洁、礼貌的确认答复，告知用户退款将被处理。
                    用户原始请求：%s""".formatted(message);
            // #3：LLM 失败不回滚已提交的人工审批决定；重试耗尽则写降级兜底答复，事务照常提交、用户总能收到终态回复。
            Attempted<String> a = withRetry("resolve",
                    () -> assistant.chat(scopedChatId, style.getLanguage(), style.getTone(),
                            style.getCitationPolicy(), style.getExtra(), userMessage),
                    ServiceTaskDelegates::degradedResolveReply);
            replyStore.save(instanceId, a.value(), a.degraded());
            if (a.degraded()) {
                audit.record(AuditEventType.REPLY_DEGRADED, Map.of(
                        "instanceId", nz(instanceId), "stage", "resolve"));
            }
            log.info("workflow resolve: reply 已生成 ({} 字) degraded={}", a.value() == null ? 0 : a.value().length(), a.degraded());
            return null;
        });
    }

    /** 驳回 → 写 reply（纯拼装，不调模型，写 {@link WorkflowReplyStore}）。 */
    public void reject(DelegateExecution execution) {
        String comment = str(execution.getVariable("comment"));
        replyStore.save(execution.getProcessInstanceId(), rejectionMessage(comment), false);
        log.info("workflow reject: 已生成驳回话术");
    }

    /** 驳回话术拼装。抽成 static 纯函数便于单测。 */
    static String rejectionMessage(String comment) {
        String reason = (comment == null || comment.isBlank()) ? "未提供具体原因" : comment.trim();
        return "很抱歉，您的退款申请未通过审核。原因：" + reason + "。如有疑问可联系人工客服进一步沟通。";
    }

    /** assess 抽取失败的降级兜底工单：强制 HIGH 转人工，summary 用原始消息（不丢用户诉求）。 */
    static Ticket degradedTicket(String message) {
        String summary = (message == null || message.isBlank()) ? "（无法解析的退款请求）" : message.trim();
        return new Ticket("退款请求（自动抽取失败，转人工）", Ticket.Priority.HIGH, "未分类", summary, List.of());
    }

    /** resolve 生成失败的降级兜底答复：明确告知已受理 + 转人工，避免用户干等。 */
    static String degradedResolveReply() {
        return "您的退款请求已受理，但系统繁忙暂未能生成完整答复。我们已转人工跟进，会尽快与您联系，给您带来不便敬请谅解。";
    }

    /**
     * 有界重试 + 降级补偿（#3）。最多尝试 {@code app.workflow.llm-max-attempts} 次（含首次），
     * 全失败则返回 {@code fallback.get()} 并标记 {@code degraded=true}——<b>绝不抛异常</b>，
     * 不让 LLM 故障回滚 Flowable 事务里已记录的人工审批决定。
     *
     * <p>抽成处理 {@link Supplier} 的纯函数，便于单测覆盖「失败 N 次→走兜底」「先失败后成功」两条路径。
     */
    <T> Attempted<T> withRetry(String label, Supplier<T> call, Supplier<T> fallback) {
        int maxAttempts = Math.max(1, props.getLlmMaxAttempts());
        long backoffMs = Math.max(0, props.getLlmRetryBackoffMs());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return new Attempted<>(call.get(), false);
            } catch (RuntimeException e) {
                last = e;
                log.warn("workflow {} LLM 调用失败（第 {}/{} 次）：{}", label, attempt, maxAttempts, e.toString());
                if (attempt < maxAttempts && backoffMs > 0) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("workflow {} LLM 调用重试耗尽，降级兜底（最后错误：{}）", label, last == null ? "interrupted" : last.toString());
        return new Attempted<>(fallback.get(), true);
    }

    /** withRetry 结果：value + 是否走了降级。 */
    record Attempted<T>(T value, boolean degraded) {}

    /**
     * 在「按流程变量 tenantId 重设的 TenantContext」下执行 work，结束后还原原线程绑定值。
     * scopes 沿用当前线程已有的（同步执行时即调用者本人的 scopes）。
     */
    private <T> T withTenant(DelegateExecution execution, Supplier<T> work) {
        String tenantId = str(execution.getVariable("tenantId"));
        String userId = str(execution.getVariable("userId"));
        TenantContext.Tenant prev = TenantContext.captureRaw();
        try {
            if (tenantId != null && !tenantId.isBlank()) {
                Set<String> scopes = prev == null ? Set.of() : prev.scopes();
                TenantContext.set(new TenantContext.Tenant(tenantId, userId, scopes));
            }
            return work.get();
        } finally {
            if (prev != null) {
                TenantContext.set(prev);
            } else {
                TenantContext.clear();
            }
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
