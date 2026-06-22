package com.lrj.langchain4j.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.a2a.protocol.A2aMessage;
import com.lrj.langchain4j.a2a.protocol.A2aTask;
import com.lrj.langchain4j.a2a.protocol.A2aTaskStatus;
import com.lrj.langchain4j.a2a.protocol.AgentCard;
import com.lrj.langchain4j.a2a.protocol.JsonRpcError;
import com.lrj.langchain4j.a2a.protocol.JsonRpcResponse;
import com.lrj.langchain4j.a2a.protocol.MessageSendParams;
import com.lrj.langchain4j.a2a.protocol.TaskPushNotificationConfig;
import com.lrj.langchain4j.a2a.protocol.TaskQueryParams;
import com.lrj.langchain4j.a2a.protocol.TaskState;
import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.ai.grounding.GroundingService;
import com.lrj.langchain4j.ai.guardrail.StreamGuard;
import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.AsyncTaskService;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A2A 协议核心：把 JSON-RPC method 路由到现有 service，做协议↔内部模型翻译。
 *
 * <p>覆盖方法：{@code message/send}（chat 同步 / multi-agent 异步）、{@code tasks/get}、
 * {@code tasks/cancel}、{@code tasks/pushNotificationConfig/set|get}。{@code message/stream}
 * 走 {@link A2aStreamService}（SSE，不经本类）。
 */
@Service
public class A2aService {

    private static final Logger log = LoggerFactory.getLogger(A2aService.class);

    public static final String SKILL_CHAT = "chat";
    public static final String SKILL_MULTI_AGENT = "multi-agent";

    private final Assistant assistant;
    private final ResolvedAssistantStyle style;
    private final GroundingService grounding;
    private final AsyncTaskService asyncTasks;
    private final A2aMapper mapper;
    private final A2aPushNotificationStore pushStore;
    private final A2aProperties props;
    private final ObjectMapper json;

    public A2aService(Assistant assistant,
                      ResolvedAssistantStyle style,
                      GroundingService grounding,
                      AsyncTaskService asyncTasks,
                      A2aMapper mapper,
                      A2aPushNotificationStore pushStore,
                      A2aProperties props,
                      ObjectMapper json) {
        this.assistant = assistant;
        this.style = style;
        this.grounding = grounding;
        this.asyncTasks = asyncTasks;
        this.mapper = mapper;
        this.pushStore = pushStore;
        this.props = props;
        this.json = json;
    }

    /** 非流式方法分派。流式（message/stream）在 controller 直接转 {@link A2aStreamService}。 */
    public JsonRpcResponse dispatch(String method, JsonNode params, Object id) {
        try {
            return switch (method) {
                case "message/send" -> handleMessageSend(params, id);
                case "tasks/get" -> handleTaskGet(params, id);
                case "tasks/cancel" -> handleTaskCancel(params, id);
                case "tasks/pushNotificationConfig/set" -> handlePushSet(params, id);
                case "tasks/pushNotificationConfig/get" -> handlePushGet(params, id);
                default -> JsonRpcResponse.error(id, JsonRpcError.methodNotFound(method));
            };
        } catch (IllegalArgumentException e) {
            return JsonRpcResponse.error(id, JsonRpcError.invalidParams(e.getMessage()));
        } catch (Exception e) {
            log.error("A2A method {} failed", method, e);
            return JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcError.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    // —— message/send ——

    private JsonRpcResponse handleMessageSend(JsonNode params, Object id) {
        MessageSendParams p = parse(params, MessageSendParams.class);
        A2aMessage msg = (p == null) ? null : p.message();
        if (msg == null || msg.textContent().isBlank()) {
            throw new IllegalArgumentException("message.parts must contain non-empty text");
        }
        String skill = skillOf(msg);
        String text = msg.textContent();

        if (SKILL_MULTI_AGENT.equals(skill)) {
            // 异步：建 task，webhookUrl 留 null（A2A push 走 A2aPushNotificationStore，不走 WebhookDispatcher）
            AsyncTask task = asyncTasks.submitMultiAgent(text, null);
            if (p.configuration() != null && p.configuration().pushNotificationConfig() != null) {
                pushStore.put(task.taskId(), p.configuration().pushNotificationConfig());
            }
            return JsonRpcResponse.success(id, mapper.toA2aTask(task));
        }

        // 默认 chat skill：同步，复用主 Assistant（guardrail + grounding 自动生效）
        String contextId = (msg.contextId() != null && !msg.contextId().isBlank())
                ? msg.contextId() : UUID.randomUUID().toString();
        String reply = grounding.applyToFreshAnswer(() -> assistant.chat(
                scopedChatId(contextId),
                style.getLanguage(), style.getTone(), style.getCitationPolicy(), style.getExtra(),
                text));
        // input-required：回复像澄清式提问时，返回 input-required 状态的 Task（带澄清问题在 status.message），
        // 而非普通 completed Message —— 给客户端标准多轮续问语义。跟 stream 路径同款判定。
        if (props.isDetectInputRequired() && StreamGuard.looksLikeClarifyingQuestion(reply)) {
            String taskId = UUID.randomUUID().toString();
            A2aTask task = new A2aTask(taskId, contextId,
                    new A2aTaskStatus(TaskState.INPUT_REQUIRED,
                            A2aMessage.agentText(reply, taskId, contextId),
                            java.time.Instant.now().toString()),
                    null, null);
            return JsonRpcResponse.success(id, task);
        }
        return JsonRpcResponse.success(id, A2aMessage.agentText(reply, null, contextId));
    }

    // —— tasks/get ——

    private JsonRpcResponse handleTaskGet(JsonNode params, Object id) {
        TaskQueryParams p = parse(params, TaskQueryParams.class);
        if (p == null || p.id() == null) throw new IllegalArgumentException("id is required");
        Optional<AsyncTask> task = asyncTasks.get(p.id());
        return task.map(t -> JsonRpcResponse.success(id, mapper.toA2aTask(t)))
                .orElseGet(() -> JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.id())));
    }

    // —— tasks/cancel ——

    private JsonRpcResponse handleTaskCancel(JsonNode params, Object id) {
        TaskQueryParams p = parse(params, TaskQueryParams.class);
        if (p == null || p.id() == null) throw new IllegalArgumentException("id is required");
        if (asyncTasks.get(p.id()).isEmpty()) {
            return JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.id()));
        }
        boolean canceled = asyncTasks.cancel(p.id());
        if (!canceled) {
            return JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcError.TASK_NOT_CANCELABLE,
                    "Task is already in a terminal state: " + p.id()));
        }
        return asyncTasks.get(p.id())
                .map(t -> JsonRpcResponse.success(id, mapper.toA2aTask(t)))
                .orElseGet(() -> JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.id())));
    }

    // —— tasks/pushNotificationConfig/set ——

    private JsonRpcResponse handlePushSet(JsonNode params, Object id) {
        TaskPushNotificationConfig p = parse(params, TaskPushNotificationConfig.class);
        if (p == null || p.taskId() == null || p.pushNotificationConfig() == null) {
            throw new IllegalArgumentException("taskId and pushNotificationConfig are required");
        }
        if (asyncTasks.get(p.taskId()).isEmpty()) {
            return JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.taskId()));
        }
        pushStore.put(p.taskId(), p.pushNotificationConfig());
        return JsonRpcResponse.success(id, p);
    }

    // —— tasks/pushNotificationConfig/get ——

    private JsonRpcResponse handlePushGet(JsonNode params, Object id) {
        TaskQueryParams p = parse(params, TaskQueryParams.class);
        if (p == null || p.id() == null) throw new IllegalArgumentException("id is required");
        if (asyncTasks.get(p.id()).isEmpty()) {
            return JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.id()));
        }
        return JsonRpcResponse.success(id,
                new TaskPushNotificationConfig(p.id(), pushStore.get(p.id()).orElse(null)));
    }

    // —— Agent Card ——

    public AgentCard agentCard() {
        List<String> text = List.of("text/plain");
        AgentCard.Skill chat = new AgentCard.Skill(
                SKILL_CHAT, "Chat", "Single-turn / multi-turn factual chat with RAG, tools and citation.",
                List.of("chat", "rag", "qa"),
                List.of("用三句话介绍 LangChain4j"), text, text);
        AgentCard.Skill multi = new AgentCard.Skill(
                SKILL_MULTI_AGENT, "Multi-agent research",
                "Plan → parallel workers → synthesize. Long-running; returned as an async Task.",
                List.of("research", "multi-agent", "async"),
                List.of("对比 PGVector / Milvus / Qdrant 三个向量库"), text, text);

        return new AgentCard(
                props.getAgentName(),
                props.getAgentDescription(),
                props.getBaseUrl() + "/a2a",
                props.getVersion(),
                "0.2.0",
                new AgentCard.Capabilities(true, true, false),
                text, text,
                List.of(chat, multi),
                Map.of("apiKey", new AgentCard.SecurityScheme(
                        "apiKey", "header", "X-Api-Key", "Per-tenant API key (see ApiKeyAuthFilter).")),
                List.of(Map.of("apiKey", List.of())));
    }

    /** message 的 metadata.skill 决定走哪个 skill；缺省 chat。给 stream service 复用，故 public。 */
    public String skillOf(A2aMessage msg) {
        if (msg != null && msg.metadata() != null) {
            Object s = msg.metadata().get("skill");
            if (s instanceof String str && !str.isBlank()) return str;
        }
        return SKILL_CHAT;
    }

    private <T> T parse(JsonNode params, Class<T> type) {
        if (params == null || params.isNull()) return null;
        return json.convertValue(params, type);
    }

    /** A2A contextId 映射到带租户前缀的 ChatMemory key —— 跟 ChatController.scopedChatId 一致的隔离思路。 */
    private static String scopedChatId(String contextId) {
        return TenantContext.current().tenantId() + ":a2a:" + contextId;
    }
}
