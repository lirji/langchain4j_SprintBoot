package com.lrj.langchain4j.channel;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.channel.feishu.FeishuIntent;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 渠道无关的客服大脑：<strong>文字进 → 意图分类 → 工作流/对话 → 文字出</strong>。
 * 把飞书 {@code FeishuChannelService.route()} 里的共性抽出来，让语音渠道（{@code voice/}）直接复用——
 * 同一套意图路由、同一套退款工作流、同一套 RAG 对话。
 *
 * <p>返回纯文字（{@link BrainReply}），不碰任何渠道出站（飞书发卡片 / 语音 TTS 各自处理）。
 * 工作流挂起（需人工审批）时返回"已转人工"播报语——因为审批是异步的，同步轮次拿不到终态；
 * 自动受理（低风险）时返回 {@code StartResult.reply()}。
 *
 * <p>{@link WorkflowService} 软依赖（{@code ObjectProvider}）：未开 {@code app.workflow.enabled} 时
 * 所有消息退化为对话，跟飞书一致。
 */
@Service
public class CustomerServiceBrain {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceBrain.class);

    private final Assistant assistant;
    private final ResolvedAssistantStyle style;
    private final ObjectProvider<WorkflowService> workflowProvider;

    public CustomerServiceBrain(Assistant assistant,
                                ResolvedAssistantStyle style,
                                ObjectProvider<WorkflowService> workflowProvider) {
        this.assistant = assistant;
        this.style = style;
        this.workflowProvider = workflowProvider;
    }

    /** 路由结果。 */
    public enum Route { WORKFLOW, CHAT }

    /** @param text 大脑回复文字（已可直接 TTS / 发文本）；route 命中路由；workflowInstanceId 仅 WORKFLOW 非空 */
    public record BrainReply(String text, Route route, String workflowInstanceId) {}

    public BrainReply reply(String tenantId, String chatId, String text) {
        WorkflowService workflow = workflowProvider.getIfAvailable();
        if (FeishuIntent.classify(text) == FeishuIntent.Route.WORKFLOW && workflow != null) {
            WorkflowService.StartResult res = workflow.start(chatId, text, null, null);
            log.info("CS brain → workflow tenant={} status={} instance={}", tenantId, res.status(), res.instanceId());
            return new BrainReply(workflowSpoken(res), Route.WORKFLOW, res.instanceId());
        }

        String scopedChatId = tenantId + ":" + chatId;
        String reply = assistant.chat(scopedChatId, style.getLanguage(), style.getTone(),
                style.getCitationPolicy(), style.getExtra(), text);
        log.info("CS brain → chat tenant={} reply={}字", tenantId, reply == null ? 0 : reply.length());
        return new BrainReply(reply == null ? "" : reply, Route.CHAT, null);
    }

    /**
     * 工作流结果 → 语音播报文案。挂起（需人工审批）播"已转人工"（审批异步、同步轮次拿不到终态）；
     * 自动受理播 {@code reply}（同步可得），兜底防 null。抽成静态便于确定性单测。
     */
    static String workflowSpoken(WorkflowService.StartResult res) {
        if (WorkflowService.STATUS_WAITING.equals(res.status())) {
            return "您的请求涉及退款或投诉，已为您转接人工审核，工单号 " + shortId(res.instanceId())
                    + "，审核结果会稍后通知您。";
        }
        return (res.reply() == null || res.reply().isBlank()) ? "已受理您的请求。" : res.reply();
    }

    private static String shortId(String id) {
        return id == null ? "?" : (id.length() <= 8 ? id : id.substring(0, 8));
    }
}
