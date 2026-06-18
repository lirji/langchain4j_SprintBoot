package com.lrj.langchain4j.voice;

import com.lrj.langchain4j.channel.CustomerServiceBrain;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VoiceConversationService 确定性逻辑单测（stub speech + 覆写 brain，不连模型/网络）：
 * ASR→脑→TTS 编排 / 空转写兜底 / TTS 前剥引用标记 / base64。
 */
class VoiceConversationServiceTest {

    /** 可控 stub：transcribe 返回预设文字，synthesize 记录入参并返回固定音频。 */
    private static class StubSpeech implements SpeechService {
        final String transcript;
        final AtomicReference<String> lastSynthInput = new AtomicReference<>();
        StubSpeech(String transcript) { this.transcript = transcript; }
        @Override public String transcribe(byte[] audio, String filename) { return transcript; }
        @Override public Speech synthesize(String text) {
            lastSynthInput.set(text);
            return new Speech(("AUDIO:" + text).getBytes(StandardCharsets.UTF_8), "audio/mpeg");
        }
    }

    private static CustomerServiceBrain brainReturning(String text, AtomicBoolean called) {
        return new CustomerServiceBrain(null, new ResolvedAssistantStyle("中文", "简洁", "c", ""), nullProvider()) {
            @Override public BrainReply reply(String tenantId, String chatId, String t) {
                called.set(true);
                return new BrainReply(text, Route.CHAT, null);
            }
        };
    }

    private static <T> ObjectProvider<T> nullProvider() {
        return new ObjectProvider<>() {
            @Override public T getObject() { throw new UnsupportedOperationException(); }
            @Override public T getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    @Test
    void fullTurn_transcribesRoutesAndSynthesizes() {
        StubSpeech speech = new StubSpeech("我要退款");
        AtomicBoolean brainCalled = new AtomicBoolean(false);
        VoiceConversationService svc = new VoiceConversationService(
                speech, brainReturning("您的退款已受理。 [doc=policy#2]", brainCalled));

        VoiceConversationService.VoiceReply r = svc.chat(new byte[]{1, 2, 3}, "q.mp3", "c1");

        assertThat(brainCalled).isTrue();
        assertThat(r.transcript()).isEqualTo("我要退款");
        assertThat(r.route()).isEqualTo("CHAT");
        // reply 文本保留引用标记（文字侧可点）
        assertThat(r.reply()).isEqualTo("您的退款已受理。 [doc=policy#2]");
        // 但喂给 TTS 的文本已剥掉引用标记（念出来不别扭）
        assertThat(speech.lastSynthInput.get()).isEqualTo("您的退款已受理。");
        // 音频是 TTS 输出的 base64
        assertThat(decode(r.audioBase64())).isEqualTo("AUDIO:您的退款已受理。");
        assertThat(r.audioContentType()).isEqualTo("audio/mpeg");
    }

    @Test
    void emptyTranscript_skipsBrain_announcesNotUnderstood() {
        StubSpeech speech = new StubSpeech("   ");   // 没听清
        AtomicBoolean brainCalled = new AtomicBoolean(false);
        VoiceConversationService svc = new VoiceConversationService(
                speech, brainReturning("不该被调用", brainCalled));

        VoiceConversationService.VoiceReply r = svc.chat(new byte[]{1}, "q.mp3", "c1");

        assertThat(brainCalled).as("空转写不进大脑，不烧 token").isFalse();
        assertThat(r.route()).isEqualTo("NONE");
        assertThat(r.transcript()).isEmpty();
        assertThat(r.reply()).contains("没有听清");
    }

    @Test
    void stripCitations_removesTags_andCollapsesSpaces() {
        assertThat(VoiceConversationService.stripCitations("答案 [doc=f#1] 见此处 [doc=g#3]"))
                .isEqualTo("答案 见此处");
        assertThat(VoiceConversationService.stripCitations(null)).isEmpty();
    }

    private static String decode(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }
}
