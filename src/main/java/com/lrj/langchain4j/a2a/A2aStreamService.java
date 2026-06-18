package com.lrj.langchain4j.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.a2a.protocol.A2aMessage;
import com.lrj.langchain4j.a2a.protocol.A2aTaskStatus;
import com.lrj.langchain4j.a2a.protocol.Artifact;
import com.lrj.langchain4j.a2a.protocol.JsonRpcResponse;
import com.lrj.langchain4j.a2a.protocol.Part;
import com.lrj.langchain4j.a2a.protocol.TaskArtifactUpdateEvent;
import com.lrj.langchain4j.a2a.protocol.TaskState;
import com.lrj.langchain4j.a2a.protocol.TaskStatusUpdateEvent;
import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.ai.grounding.GroundingService;
import com.lrj.langchain4j.ai.guardrail.StreamGuard;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A2A {@code message/stream}（chat skill）：把 {@link Assistant#chatStream} 的 {@link TokenStream}
 * 翻成 A2A 流式事件序列，每个 SSE 帧体是包着事件的 JSON-RPC response：
 * <ol>
 *   <li>{@code status-update} WORKING（final=false）—— 开流</li>
 *   <li>逐 token {@code artifact-update}（append=true）—— 增量产出</li>
 *   <li>{@code status-update} COMPLETED / FAILED（final=true）—— 收口</li>
 * </ol>
 * 仿 {@code ChatController.chatStream} + {@code TaskSseService} 的 SSE 写法。
 */
@Service
public class A2aStreamService {

    private static final Logger log = LoggerFactory.getLogger(A2aStreamService.class);
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final Assistant assistant;
    private final ResolvedAssistantStyle style;
    private final ObjectMapper json;
    private final A2aProperties props;
    private final GroundingService grounding;

    public A2aStreamService(Assistant assistant, ResolvedAssistantStyle style, ObjectMapper json,
                            A2aProperties props, GroundingService grounding) {
        this.assistant = assistant;
        this.style = style;
        this.json = json;
        this.props = props;
        this.grounding = grounding;
    }

    public SseEmitter stream(A2aMessage msg, Object rpcId) {
        if (msg == null || msg.textContent().isBlank()) {
            throw new IllegalArgumentException("message.parts must contain non-empty text");
        }
        String contextId = (msg.contextId() != null && !msg.contextId().isBlank())
                ? msg.contextId() : UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String artifactId = UUID.randomUUID().toString();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 缓冲完整答案做收口（PII 告警 + input-required 判定）；cancelled 标记客户端断开后停转发。
        // 同 /chat/stream：TokenStream 无取消句柄，断开后只能停转发，不能中止上游生成。
        StringBuilder full = new StringBuilder();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<List<Content>> retrieved = new AtomicReference<>();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> { cancelled.set(true); emitter.complete(); });
        emitter.onError(e -> cancelled.set(true));

        send(emitter, rpcId, new TaskStatusUpdateEvent(taskId, contextId,
                A2aTaskStatus.of(TaskState.WORKING, Instant.now().toString()), false));

        TokenStream tokenStream = assistant.chatStream(
                scopedChatId(contextId),
                style.getLanguage(), style.getTone(), style.getCitationPolicy(), style.getExtra(),
                msg.textContent());

        tokenStream
                .onRetrieved(retrieved::set)
                .onPartialResponse(token -> {
                    if (cancelled.get()) return;   // 客户端已断开，停止转发
                    full.append(token);
                    Artifact chunk = new Artifact(artifactId, "answer", List.of(Part.text(token)));
                    send(emitter, rpcId, new TaskArtifactUpdateEvent(taskId, contextId, chunk, true, false));
                })
                .onCompleteResponse(resp -> {
                    if (cancelled.get()) return;
                    String answer = full.toString();
                    // 流式后处理：PII / grounding 命中时追加 artifact 告警（无法回收已发 token）
                    String pii = StreamGuard.piiWarningOrNull(answer);
                    if (pii != null) {
                        log.warn("PII detected in A2A streamed answer task={} (warn-only)", taskId);
                        Artifact warn = new Artifact(artifactId, "answer", List.of(Part.text("\n\n" + pii)));
                        send(emitter, rpcId, new TaskArtifactUpdateEvent(taskId, contextId, warn, true, false));
                    }
                    String groundWarn = grounding.streamWarningOrNull(retrieved.get(), answer);
                    if (groundWarn != null) {
                        Artifact warn = new Artifact(artifactId, "answer", List.of(Part.text(groundWarn)));
                        send(emitter, rpcId, new TaskArtifactUpdateEvent(taskId, contextId, warn, true, false));
                    }
                    // input-required：回复像澄清式提问时把终态置为 INPUT_REQUIRED（给客户端多轮续问语义）
                    TaskState finalState = (props.isDetectInputRequired()
                            && StreamGuard.looksLikeClarifyingQuestion(answer))
                            ? TaskState.INPUT_REQUIRED : TaskState.COMPLETED;
                    send(emitter, rpcId, new TaskStatusUpdateEvent(taskId, contextId,
                            A2aTaskStatus.of(finalState, Instant.now().toString()), true));
                    emitter.complete();
                })
                .onError(err -> {
                    log.error("A2A stream error task={}", taskId, err);
                    send(emitter, rpcId, new TaskStatusUpdateEvent(taskId, contextId,
                            new A2aTaskStatus(TaskState.FAILED,
                                    A2aMessage.agentText(String.valueOf(err.getMessage()), taskId, contextId),
                                    Instant.now().toString()), true));
                    emitter.complete();
                })
                .start();

        return emitter;
    }

    /** 把事件包成 JSON-RPC response 序列化成字符串发出（裸 token 那套不适用，A2A 帧是结构化对象）。 */
    private void send(SseEmitter emitter, Object rpcId, Object event) {
        try {
            String payload = json.writeValueAsString(JsonRpcResponse.success(rpcId, event));
            emitter.send(SseEmitter.event().data(payload));
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.warn("A2A stream serialize/send failed: {}", e.toString());
        }
    }

    private static String scopedChatId(String contextId) {
        return TenantContext.current().tenantId() + ":a2a:" + contextId;
    }
}
