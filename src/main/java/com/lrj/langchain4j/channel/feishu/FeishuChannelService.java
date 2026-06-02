package com.lrj.langchain4j.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.security.TenantContext;
import com.lrj.langchain4j.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 飞书入站编排（M1.B 意图路由）：把已解密的事件归一成 {@code (tenantId, openId, text)}，按意图分流：
 * 退款/投诉 → refund 工作流，其余 → Assistant 对话。处理在 {@code feishuExecutor} 异步跑
 * （{@code @Async}），让 {@code FeishuController} 先 200 满足飞书 ~5s ack；完成后主动回推用户。
 *
 * <ul>
 *   <li><b>CHAT</b>：直接 {@code Assistant.chat} → 回推答复。</li>
 *   <li><b>WORKFLOW 低风险</b>：{@code start} 同步走完到 COMPLETED，<b>不在此处回推</b>——由
 *       {@code WorkflowTerminalEvent} → {@code FeishuReplyListener} 统一回推（避免重复）。</li>
 *   <li><b>WORKFLOW 高风险</b>：{@code start} 返回 WAITING_APPROVAL，先回推一句"已转人工审核"ack，
 *       并（若配了 {@code approverChatId}）推审批卡片；最终结果待审批人 complete 后由终态事件回推。</li>
 * </ul>
 *
 * <p>工作流可选（{@code ObjectProvider}）：未开 {@code app.workflow.enabled} 时退化为纯对话（所有消息走 CHAT）。
 */
@Service
@ConditionalOnProperty(name = "app.channel.feishu.enabled", havingValue = "true")
public class FeishuChannelService {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannelService.class);

    private final FeishuProperties props;
    private final FeishuClient client;
    private final Assistant assistant;
    private final ResolvedAssistantStyle style;
    private final ObjectProvider<WorkflowService> workflowProvider;
    private final ObjectMapper mapper;

    public FeishuChannelService(FeishuProperties props, FeishuClient client, Assistant assistant,
                                ResolvedAssistantStyle style, ObjectProvider<WorkflowService> workflowProvider,
                                ObjectMapper mapper) {
        this.props = props;
        this.client = client;
        this.assistant = assistant;
        this.style = style;
        this.workflowProvider = workflowProvider;
        this.mapper = mapper;
    }

    /**
     * 处理 {@code im.message.receive_v1} 事件（已解密的完整事件 JSON）。提取 open_id / 文本 / message_id，
     * 异步分流。非文本消息忽略。
     */
    @Async("feishuExecutor")
    public void handleMessageEvent(JsonNode root) {
        JsonNode event = root.path("event");
        JsonNode message = event.path("message");
        String msgType = message.path("message_type").asText("");
        if (!"text".equals(msgType)) {
            return; // v1 只处理文本
        }
        String openId = event.path("sender").path("sender_id").path("open_id").asText("");
        String messageId = message.path("message_id").asText("");
        String text = extractText(message.path("content").asText(""));
        if (openId.isBlank() || text.isBlank()) {
            return;
        }
        String tenantId = props.getTenant();
        String chatId = FeishuReplyListener.FEISHU_PREFIX + openId;

        TenantContext.Tenant prev = TenantContext.captureRaw();
        try {
            TenantContext.set(new TenantContext.Tenant(tenantId, chatId, Set.of()));
            route(tenantId, openId, chatId, text, messageId);
        } catch (Exception e) {
            log.warn("飞书入站处理异常 openId={}：{}", openId, e.toString());
            client.sendText(openId, "抱歉，系统繁忙，请稍后再试。");
        } finally {
            if (prev != null) TenantContext.set(prev); else TenantContext.clear();
        }
    }

    private void route(String tenantId, String openId, String chatId, String text, String messageId) {
        WorkflowService workflow = workflowProvider.getIfAvailable();
        FeishuIntent.Route r = FeishuIntent.classify(text);
        if (r == FeishuIntent.Route.WORKFLOW && workflow != null) {
            // 退款/投诉 → 工作流。message_id 当 dedupeId（飞书重推同一条只起一个流程，复用 #2 幂等）
            WorkflowService.StartResult res = workflow.start(chatId, text, messageId, null);
            if (WorkflowService.STATUS_WAITING.equals(res.status())) {
                client.sendText(openId, "您的请求涉及退款/投诉，已转人工审核（工单 " + shortId(res.instanceId())
                        + "）。审核结果会第一时间在这里通知您。");
                pushApprovalCardIfConfigured(tenantId, res, text);
            }
            // 低风险 COMPLETED：reply 由 WorkflowTerminalEvent → FeishuReplyListener 回推，这里不重复推
            log.info("飞书→工作流 openId={} status={} instanceId={}", openId, res.status(), res.instanceId());
            return;
        }
        // 其余 → 对话
        String scopedChatId = tenantId + ":" + chatId;
        String reply = assistant.chat(scopedChatId, style.getLanguage(), style.getTone(),
                style.getCitationPolicy(), style.getExtra(), text);
        client.sendText(openId, reply);
        log.info("飞书→对话 openId={} reply={}字", openId, reply == null ? 0 : reply.length());
    }

    /**
     * 处理审批卡片按钮回调：从 action.value 取 {@code taskId}/{@code approved}/{@code tenant}，调
     * {@code WorkflowService.complete}。完成后最终答复经 {@code WorkflowTerminalEvent} 回推发起用户。
     *
     * <p><b>注意</b>：飞书卡片回调 schema 随版本有差异，这里按 {@code event.action.value} 防御性解析；
     * 接入时请对照实际卡片结构核对路径。
     */
    public void handleCardAction(JsonNode root) {
        JsonNode value = root.path("event").path("action").path("value");
        if (value.isMissingNode()) {
            value = root.path("action").path("value"); // 兼容旧结构
        }
        String taskId = value.path("taskId").asText("");
        String tenant = value.path("tenant").asText(props.getTenant());
        boolean approved = value.path("approved").asBoolean(false);
        if (taskId.isBlank()) {
            return;
        }
        WorkflowService workflow = workflowProvider.getIfAvailable();
        if (workflow == null) {
            return;
        }
        TenantContext.Tenant prev = TenantContext.captureRaw();
        try {
            // 审批人身份：飞书操作者，带 approve scope（卡片闭环里飞书侧即审批入口）
            TenantContext.set(new TenantContext.Tenant(tenant, "feishu-approver", Set.of("approve")));
            workflow.complete(taskId, approved, "飞书卡片审批");
            log.info("飞书卡片审批 taskId={} approved={}", taskId, approved);
        } catch (Exception e) {
            log.warn("飞书卡片审批失败 taskId={}：{}", taskId, e.toString());
        } finally {
            if (prev != null) TenantContext.set(prev); else TenantContext.clear();
        }
    }

    /** 配了 approverChatId 才推审批卡片：两个按钮的 value 带 {taskId, approved, tenant}，点击回调 complete。 */
    private void pushApprovalCardIfConfigured(String tenantId, WorkflowService.StartResult res, String text) {
        if (props.getApproverChatId() == null || props.getApproverChatId().isBlank() || res.taskId() == null) {
            return;
        }
        try {
            String card = buildApprovalCard(tenantId, res.taskId(), res.instanceId(), res.priority(), text);
            client.sendCardToChat(props.getApproverChatId(), card);
        } catch (Exception e) {
            log.warn("推审批卡片失败 taskId={}：{}", res.taskId(), e.toString());
        }
    }

    /** 极简审批交互卡片 JSON：标题 + 工单摘要 + 通过/驳回两个按钮（value 携带回调所需字段）。 */
    private String buildApprovalCard(String tenant, String taskId, String instanceId, String priority, String text)
            throws Exception {
        var approve = mapper.createObjectNode();
        approve.put("taskId", taskId).put("tenant", tenant).put("approved", true);
        var reject = mapper.createObjectNode();
        reject.put("taskId", taskId).put("tenant", tenant).put("approved", false);

        var card = mapper.createObjectNode();
        var header = card.putObject("header");
        header.putObject("title").put("tag", "plain_text")
                .put("content", "退款审批 · " + priority + " · 工单 " + shortId(instanceId));
        var elements = card.putArray("elements");
        elements.addObject().put("tag", "div").putObject("text")
                .put("tag", "lark_md").put("content", "用户诉求：" + text);
        var actions = elements.addObject();
        actions.put("tag", "action");
        var btns = actions.putArray("actions");
        var pass = btns.addObject();
        pass.put("tag", "button").put("type", "primary").set("value", approve);
        pass.putObject("text").put("tag", "plain_text").put("content", "通过");
        var deny = btns.addObject();
        deny.put("tag", "button").put("type", "danger").set("value", reject);
        deny.putObject("text").put("tag", "plain_text").put("content", "驳回");
        return mapper.writeValueAsString(card);
    }

    /** 解析飞书文本消息 content（{@code {"text":"..."}}）。 */
    private String extractText(String contentJson) {
        try {
            return mapper.readTree(contentJson).path("text").asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
