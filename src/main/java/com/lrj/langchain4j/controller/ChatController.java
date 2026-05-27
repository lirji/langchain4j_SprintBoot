package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.ai.CategoryChatService;
import com.lrj.langchain4j.ai.extract.Extractor;
import com.lrj.langchain4j.ai.extract.Ticket;
import com.lrj.langchain4j.ai.mcp.McpAssistant;
import com.lrj.langchain4j.ai.multiagent.MultiAgentService;
import com.lrj.langchain4j.ai.reflexion.ReflexiveService;
import com.lrj.langchain4j.ai.routing.QueryRouterService;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.rag.RagIngestionService;
import org.springframework.beans.factory.ObjectProvider;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Assistant assistant;
    private final ResolvedAssistantStyle assistantProps;
    private final RagIngestionService ragIngestionService;
    private final CategoryChatService categoryChatService;
    private final Extractor extractor;
    private final ReflexiveService reflexiveService;
    private final MultiAgentService multiAgentService;
    private final ObjectProvider<McpAssistant> mcpAssistantProvider;
    private final ObjectProvider<QueryRouterService> queryRouterProvider;

    public ChatController(Assistant assistant,
                          ResolvedAssistantStyle assistantProps,
                          RagIngestionService ragIngestionService,
                          CategoryChatService categoryChatService,
                          Extractor extractor,
                          ReflexiveService reflexiveService,
                          MultiAgentService multiAgentService,
                          ObjectProvider<McpAssistant> mcpAssistantProvider,
                          ObjectProvider<QueryRouterService> queryRouterProvider) {
        this.assistant = assistant;
        this.assistantProps = assistantProps;
        this.ragIngestionService = ragIngestionService;
        this.categoryChatService = categoryChatService;
        this.extractor = extractor;
        this.reflexiveService = reflexiveService;
        this.multiAgentService = multiAgentService;
        this.mcpAssistantProvider = mcpAssistantProvider;
        this.queryRouterProvider = queryRouterProvider;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestParam(defaultValue = "default") String chatId,
                                    @RequestBody Map<String, String> body) {
        String reply = assistant.chat(chatId,
                assistantProps.getLanguage(),
                assistantProps.getTone(),
                assistantProps.getCitationPolicy(),
                assistantProps.getExtra(),
                body.getOrDefault("message", ""));
        return Map.of("chatId", chatId, "reply", reply);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam(defaultValue = "default") String chatId,
                                 @RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(120_000L);
        TokenStream tokenStream = assistant.chatStream(chatId,
                assistantProps.getLanguage(),
                assistantProps.getTone(),
                assistantProps.getCitationPolicy(),
                assistantProps.getExtra(),
                body.getOrDefault("message", ""));

        tokenStream
                .onPartialResponse(token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onCompleteResponse(resp -> {
                    try {
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
        String reply = categoryChatService.chatInCategory(chatId, category, body.getOrDefault("message", ""));
        return Map.of("chatId", chatId, "category", category, "reply", reply);
    }

    @PostMapping("/rag/ingest")
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
        return router.route(chatId, body.getOrDefault("message", ""));
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
