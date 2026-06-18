package com.lrj.langchain4j.voice;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 半流式语音（V2 第一步）：上行一次整段音频，下行 SSE 流式推回——
 * <ol>
 *   <li>整段 ASR → {@code transcript} 事件</li>
 *   <li>{@code Assistant.chatStream} 流式生成回复 token，{@link SentenceChunker} 凑齐一句就 TTS、
 *       发一个 {@code audio-chunk} 事件（{@code {text, audioContentType, audioBase64}}），客户端边收边播</li>
 *   <li>收口发剩余尾句 + {@code done}</li>
 * </ol>
 *
 * <p><strong>这是"半双工流式"</strong>：上行整段（非流式 ASR）、下行流式（分句 TTS）。回复不用等整段
 * 生成完，延迟比 turn-based {@code /voice/chat} 明显低。<strong>不做</strong>全双工 barge-in（边说边打断）——
 * 那要 WebSocket/WebRTC + VAD，留 V3。也<strong>不</strong>经工作流意图路由（流式只走对话；退款类用 turn-based）。
 *
 * <p>断连取消同 {@code /chat/stream}：客户端断开后停止转发；但 {@code TokenStream.start()} 无取消句柄，
 * 上游 LLM 仍会生成完。
 */
public class VoiceStreamService {

    private static final Logger log = LoggerFactory.getLogger(VoiceStreamService.class);
    private static final String NOT_UNDERSTOOD = "抱歉，我没有听清，请您再说一遍。";

    private final Assistant assistant;
    private final ResolvedAssistantStyle style;
    private final SpeechService speech;
    private final int minChars;

    public VoiceStreamService(Assistant assistant, ResolvedAssistantStyle style, SpeechService speech, int minChars) {
        this.assistant = assistant;
        this.style = style;
        this.speech = speech;
        this.minChars = minChars;
    }

    public SseEmitter stream(byte[] audio, String filename, String rawChatId) {
        String tenant = TenantContext.current().tenantId();
        String scopedChatId = tenant + ":" + rawChatId;
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> { cancelled.set(true); emitter.complete(); });
        emitter.onError(e -> cancelled.set(true));

        // ASR 整段（阻塞）—— 半流式：转写不流式，生成才流式
        String transcript;
        try {
            transcript = speech.transcribe(audio, filename);
            emitter.send(SseEmitter.event().name("transcript").data(transcript == null ? "" : transcript));
        } catch (Exception e) {
            emitter.completeWithError(e);
            return emitter;
        }

        if (transcript == null || transcript.isBlank()) {
            try {
                emitSentence(emitter, NOT_UNDERSTOOD);
                emitter.send(SseEmitter.event().name("done").data(""));
            } catch (IOException ignored) { }
            emitter.complete();
            return emitter;
        }

        SentenceChunker chunker = new SentenceChunker(minChars);
        TokenStream ts = assistant.chatStream(scopedChatId,
                style.getLanguage(), style.getTone(), style.getCitationPolicy(), style.getExtra(), transcript);

        ts.onPartialResponse(token -> {
                    if (cancelled.get()) return;
                    try {
                        for (String sentence : chunker.feed(token)) emitSentence(emitter, sentence);
                    } catch (IOException e) {
                        cancelled.set(true);
                        emitter.completeWithError(e);
                    }
                })
                .onCompleteResponse(resp -> {
                    if (cancelled.get()) return;
                    try {
                        String tail = chunker.flush();
                        if (!tail.isBlank()) emitSentence(emitter, tail);
                        emitter.send(SseEmitter.event().name("done").data(""));
                    } catch (IOException ignored) { }
                    emitter.complete();
                })
                .onError(err -> {
                    log.error("voice stream error", err);
                    emitter.completeWithError(err);
                })
                .start();
        return emitter;
    }

    /** 一句 → 剥引用标记 → TTS → 一个 audio-chunk 事件。 */
    private void emitSentence(SseEmitter emitter, String sentence) throws IOException {
        String spoken = VoiceConversationService.stripCitations(sentence);
        SpeechService.Speech tts = speech.synthesize(spoken);
        emitter.send(SseEmitter.event().name("audio-chunk").data(Map.of(
                "text", sentence,
                "audioContentType", tts.contentType(),
                "audioBase64", Base64.getEncoder().encodeToString(tts.audio()))));
    }
}
