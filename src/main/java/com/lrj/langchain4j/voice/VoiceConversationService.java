package com.lrj.langchain4j.voice;

import com.lrj.langchain4j.channel.CustomerServiceBrain;
import com.lrj.langchain4j.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 语音对话编排：<strong>ASR → {@link CustomerServiceBrain} → TTS</strong>。
 * 大脑、工作流、RAG、多租户全部复用，这里只负责语音前后两端 + 两个语音特有的处理：
 * <ul>
 *   <li><b>空转写兜底</b>：没听清（转写为空）就不进大脑、不烧 token，直接播一句"没听清"。</li>
 *   <li><b>引用标记剥离</b>：RAG 回复里的 {@code [doc=file#3]} 念出来很怪，TTS 前剥掉；
 *       但 transcript 返回的回复文本里保留（文字侧仍可点引用）。</li>
 * </ul>
 */
public class VoiceConversationService {

    private static final Logger log = LoggerFactory.getLogger(VoiceConversationService.class);
    private static final Pattern CITATION = Pattern.compile("\\[doc=[^\\]]+\\]");
    private static final String NOT_UNDERSTOOD = "抱歉，我没有听清，请您再说一遍。";

    private final SpeechService speech;
    private final CustomerServiceBrain brain;

    public VoiceConversationService(SpeechService speech, CustomerServiceBrain brain) {
        this.speech = speech;
        this.brain = brain;
    }

    /**
     * @param audio    上传的音频字节
     * @param filename 原始文件名（给 ASR 嗅探格式）
     * @param chatId   会话 id（隔离多轮记忆，同 {@code /chat}）
     */
    public VoiceReply chat(byte[] audio, String filename, String chatId) {
        String tenantId = TenantContext.current().tenantId();

        String transcript = speech.transcribe(audio, filename);
        if (transcript == null || transcript.isBlank()) {
            SpeechService.Speech tts = speech.synthesize(NOT_UNDERSTOOD);
            return new VoiceReply("", NOT_UNDERSTOOD, "NONE",
                    base64(tts.audio()), tts.contentType());
        }

        CustomerServiceBrain.BrainReply br = brain.reply(tenantId, chatId, transcript);
        String spoken = stripCitations(br.text());
        SpeechService.Speech tts = speech.synthesize(spoken);

        log.info("voice chat tenant={} chatId={} route={} transcript={}字 reply={}字",
                tenantId, chatId, br.route(), transcript.length(), br.text().length());
        return new VoiceReply(transcript, br.text(), br.route().name(),
                base64(tts.audio()), tts.contentType());
    }

    /** 只做 ASR（调试 / 纯转写需求）。 */
    public String transcribeOnly(byte[] audio, String filename) {
        return speech.transcribe(audio, filename);
    }

    public static String stripCitations(String text) {
        if (text == null) return "";
        return CITATION.matcher(text).replaceAll("").replaceAll("\\s{2,}", " ").trim();
    }

    private static String base64(byte[] bytes) {
        return bytes == null ? "" : Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * @param transcript        ASR 转写的用户原话
     * @param reply             大脑回复文本（保留引用标记，文字侧用）
     * @param route             命中路由：CHAT / WORKFLOW / NONE（空转写）
     * @param audioBase64       TTS 语音回复（base64，已剥引用标记）
     * @param audioContentType  音频 content-type
     */
    public record VoiceReply(String transcript, String reply, String route,
                             String audioBase64, String audioContentType) {}
}
