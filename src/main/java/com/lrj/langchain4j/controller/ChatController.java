package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.ai.CategoryChatService;
import com.lrj.langchain4j.ai.guardrail.StreamGuard;
import com.lrj.langchain4j.ai.grounding.GroundingService;
import com.lrj.langchain4j.ai.extract.Extractor;
import com.lrj.langchain4j.ai.extract.Ticket;
import com.lrj.langchain4j.ai.mcp.McpAssistant;
import com.lrj.langchain4j.ai.multiagent.MultiAgentService;
import com.lrj.langchain4j.ai.reflexion.ReflexiveService;
import com.lrj.langchain4j.ai.routing.QueryRouterService;
import com.lrj.langchain4j.async.AsyncTask;
import com.lrj.langchain4j.async.AsyncTaskService;
import com.lrj.langchain4j.cache.semantic.SemanticCache;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.rag.RagIngestionService;
import com.lrj.langchain4j.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Assistant assistant;
    private final ResolvedAssistantStyle assistantProps;
    private final GroundingService groundingService;
    private final RagIngestionService ragIngestionService;
    private final CategoryChatService categoryChatService;
    private final Extractor extractor;
    private final ReflexiveService reflexiveService;
    private final MultiAgentService multiAgentService;
    private final ObjectProvider<McpAssistant> mcpAssistantProvider;
    private final ObjectProvider<QueryRouterService> queryRouterProvider;
    private final AsyncTaskService asyncTasks;
    // 语义响应缓存（app.cache.semantic.enabled，默认关）：软依赖，关闭时 getIfAvailable() 返回 null，/chat 行为不变
    private final ObjectProvider<SemanticCache> semanticCacheProvider;

    public ChatController(Assistant assistant,
                          ResolvedAssistantStyle assistantProps,
                          GroundingService groundingService,
                          RagIngestionService ragIngestionService,
                          CategoryChatService categoryChatService,
                          Extractor extractor,
                          ReflexiveService reflexiveService,
                          MultiAgentService multiAgentService,
                          ObjectProvider<McpAssistant> mcpAssistantProvider,
                          ObjectProvider<QueryRouterService> queryRouterProvider,
                          AsyncTaskService asyncTasks,
                          ObjectProvider<SemanticCache> semanticCacheProvider) {
        this.assistant = assistant;
        this.assistantProps = assistantProps;
        this.groundingService = groundingService;
        this.ragIngestionService = ragIngestionService;
        this.categoryChatService = categoryChatService;
        this.extractor = extractor;
        this.reflexiveService = reflexiveService;
        this.multiAgentService = multiAgentService;
        this.mcpAssistantProvider = mcpAssistantProvider;
        this.queryRouterProvider = queryRouterProvider;
        this.asyncTasks = asyncTasks;
        this.semanticCacheProvider = semanticCacheProvider;
    }

    /**
     * 把用户传的 chatId 用 tenantId 前缀包一层 —— ChatMemory store（包括 Redis）按 key 前缀天然
     * 隔离。controller 是最薄的 tenant-binding 层；服务内部无需感知 tenant 概念。
     */
    private static String scopedChatId(String rawChatId) {
        return TenantContext.current().tenantId() + ":" + rawChatId;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestParam(defaultValue = "default") String chatId,
                                    @RequestBody Map<String, String> body) {
        String scoped = scopedChatId(chatId);
        String message = body.getOrDefault("message", "");

        // 语义缓存命中即 0 LLM token 短路（按租户桶找 cosine>=阈值 的历史问答）。关闭时 cache==null，直通。
        SemanticCache cache = semanticCacheProvider.getIfAvailable();
        if (cache != null) {
            Optional<String> cached = cache.lookup(message);
            if (cached.isPresent()) {
                return Map.of("chatId", chatId, "reply", cached.get(), "cached", "true");
            }
        }

        // Function 重载：grounding REGENERATE 模式下 hint 携带纠正指令，拼进 message 触发真正的纠正重生成
        // （WARN/REFUSE 模式 hint 恒为空串，等价于原来的单次调用）。
        String reply = groundingService.applyToFreshAnswer(hint -> assistant.chat(scoped,
                assistantProps.getLanguage(),
                assistantProps.getTone(),
                assistantProps.getCitationPolicy(),
                assistantProps.getExtra(),
                message + hint));

        if (cache != null) {
            cache.put(message, reply);
        }
        return Map.of("chatId", chatId, "reply", reply);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam(defaultValue = "default") String chatId,
                                 @RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(120_000L);
        // 缓冲完整答案做收口后处理（PII 告警）；cancelled 标记客户端断开后停止转发。
        // 注意：langchain4j 1.13 的 TokenStream.start() 返回 void、不暴露取消句柄，所以断开后
        // 只能停止向 emitter 转发 + 跳过后处理，无法真正中止上游 LLM 生成（生成仍会跑完）。
        StringBuilder full = new StringBuilder();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<List<Content>> retrieved = new AtomicReference<>();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> { cancelled.set(true); emitter.complete(); });
        emitter.onError(e -> cancelled.set(true));

        TokenStream tokenStream = assistant.chatStream(scopedChatId(chatId),
                assistantProps.getLanguage(),
                assistantProps.getTone(),
                assistantProps.getCitationPolicy(),
                assistantProps.getExtra(),
                body.getOrDefault("message", ""));

        tokenStream
                // 捕获本轮检索片段（流式回调线程拿不到 RetrievedSourcesContext ThreadLocal，改这里捕获）
                .onRetrieved(retrieved::set)
                .onPartialResponse(token -> {
                    if (cancelled.get()) return;   // 客户端已断开，停止转发
                    full.append(token);
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        cancelled.set(true);
                        emitter.completeWithError(e);
                    }
                })
                .onCompleteResponse(resp -> {
                    if (cancelled.get()) return;
                    try {
                        // 流式后处理：token 已发出无法重写，命中只能追加 warning 事件
                        String answer = full.toString();
                        String pii = StreamGuard.piiWarningOrNull(answer);
                        if (pii != null) {
                            log.warn("PII detected in streamed answer (warn-only, cannot redact post-stream)");
                            emitter.send(SseEmitter.event().name("warning").data(pii));
                        }
                        String grounding = groundingService.streamWarningOrNull(retrieved.get(), answer);
                        if (grounding != null) {
                            emitter.send(SseEmitter.event().name("grounding-warning").data(grounding.strip()));
                        }
                        emitter.send(SseEmitter.event().name("done").data(""));
                    } catch (IOException ignored) {
                        // emitter may already be closed
                    }
                    emitter.complete();
                })
                .onError(err -> {
                    log.error("streaming error", err);
                    emitter.completeWithError(err);
                })
                .start();

        return emitter;
    }

    @PostMapping("/chat/category")
    public Map<String, String> chatInCategory(@RequestParam(defaultValue = "default") String chatId,
                                              @RequestParam String category,
                                              @RequestBody Map<String, String> body) {
        String reply = categoryChatService.chatInCategory(scopedChatId(chatId), category, body.getOrDefault("message", ""));
        return Map.of("chatId", chatId, "category", category, "reply", reply);
    }

    /** ingest 是有写权限的破坏性操作（污染向量库），只放给带 {@code SCOPE_ingest} 的 key。 */
    @PostMapping("/rag/ingest")
    @PreAuthorize("hasAuthority('SCOPE_ingest')")
    public Map<String, Object> ingest(@RequestParam(required = false) String category) {
        int count = ragIngestionService.ingestFromConfiguredDir(category);
        return Map.of("ingestedDocuments", count, "category", category == null ? "" : category);
    }

    @PostMapping("/extract/ticket")
    public Ticket extractTicket(@RequestBody Map<String, String> body) {
        return extractor.extractTicket(body.getOrDefault("text", ""));
    }

    @PostMapping("/chat/reflexive")
    public ReflexiveService.Result chatReflexive(@RequestBody Map<String, String> body) {
        return reflexiveService.chatReflexive(body.getOrDefault("message", ""));
    }

    /**
     * Reflexion SSE stream：按阶段 emit attempt-start / answer-token / critique / done。
     * 见 {@link ReflexiveService#chatReflexiveStream}。
     */
    @PostMapping(value = "/chat/reflexive/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatReflexiveStream(@RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(180_000L);
        reflexiveService.chatReflexiveStream(body.getOrDefault("message", ""), emitter);
        return emitter;
    }

    @PostMapping("/chat/multi-agent")
    public MultiAgentService.Run chatMultiAgent(@RequestBody Map<String, String> body) {
        return multiAgentService.run(body.getOrDefault("message", ""));
    }

    /**
     * 异步版的 multi-agent —— 立即返回 {@code AsyncTask}（含 taskId + PENDING 状态），
     * 实际执行投到 {@code multiAgentExecutor} 后台。客户端拿结果的 3 种方式：
     * <ul>
     *   <li>{@code GET /tasks/{taskId}}            — 轮询</li>
     *   <li>{@code GET /tasks/{taskId}/stream}     — SSE 长连接推送（同进程，适合浏览器/CLI）</li>
     *   <li>body 传 {@code webhookUrl}              — 终态时回调（server-to-server 集成）</li>
     * </ul>
     * 三种可以同时启用；webhook 带 HMAC-SHA256 签名，签名 secret 在 yml 里配。
     *
     * <p>body 接受 {@code {"message": "...", "webhookUrl": "https://..."}}。
     * 后者可选；不传 = 只走轮询/SSE 路径。
     */
    @PostMapping("/chat/multi-agent/async")
    public AsyncTask chatMultiAgentAsync(@RequestBody Map<String, String> body) {
        return asyncTasks.submitMultiAgent(
                body.getOrDefault("message", ""),
                body.get("webhookUrl"));
    }

    /**
     * Multi-agent SSE stream：按阶段 emit plan / worker-result / synthesis-token / done。
     * 见 {@link MultiAgentService#runStream}。
     */
    @PostMapping(value = "/chat/multi-agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatMultiAgentStream(@RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(180_000L);
        multiAgentService.runStream(body.getOrDefault("message", ""), emitter);
        return emitter;
    }

    @PostMapping("/chat/mcp")
    public Map<String, String> chatMcp(@RequestBody Map<String, String> body) {
        McpAssistant assistant = mcpAssistantProvider.getIfAvailable();
        if (assistant == null) {
            return Map.of("error", "MCP not enabled. Set app.mcp.enabled=true and configure the transport.");
        }
        return Map.of("reply", assistant.chat(body.getOrDefault("message", "")));
    }

    /**
     * Query routing：classifier 把 query 分到 RAG / TOOL / CHAT，分别走 Assistant 或 BareAssistant。
     * 需要 {@code app.query-router.enabled=true} 才装配，否则返回 503。
     */
    @PostMapping("/chat/auto")
    public Object chatAuto(@RequestParam(defaultValue = "default") String chatId,
                           @RequestBody Map<String, String> body) {
        QueryRouterService router = queryRouterProvider.getIfAvailable();
        if (router == null) {
            return Map.of("error", "Query router not enabled. Set app.query-router.enabled=true.");
        }
        return router.route(scopedChatId(chatId), body.getOrDefault("message", ""));
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
