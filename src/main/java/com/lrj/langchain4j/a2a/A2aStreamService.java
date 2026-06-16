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
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    public A2aStreamService(Assistant assistant, ResolvedAssistantStyle style, ObjectMapper json) {
        this.assistant = assistant;
        this.style = style;
        this.json = json;
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

        send(emitter, rpcId, new TaskStatusUpdateEvent(taskId, contextId,
                A2aTaskStatus.of(TaskState.WORKING, Instant.now().toString()), false));

        TokenStream tokenStream = assistant.chatStream(
                scopedChatId(contextId),
                style.getLanguage(), style.getTone(), style.getCitationPolicy(), style.getExtra(),
                msg.textContent());

        tokenStream
                .onPartialResponse(token -> {
                    Artifact chunk = new Artifact(artifactId, "answer", List.of(Part.text(token)));
                    send(emitter, rpcId, new TaskArtifactUpdateEvent(taskId, contextId, chunk, true, false));
                })
                .onCompleteResponse(resp -> {
                    send(emitter, rpcId, new TaskStatusUpdateEvent(taskId, contextId,
                            A2aTaskStatus.of(TaskState.COMPLETED, Instant.now().toString()), true));
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
