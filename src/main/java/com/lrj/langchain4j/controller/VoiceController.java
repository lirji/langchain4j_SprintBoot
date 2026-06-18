package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.voice.VoiceConversationService;
import com.lrj.langchain4j.voice.VoiceProperties;
import com.lrj.langchain4j.voice.VoiceStreamService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 语音客服入口（{@code app.voice.enabled=true} 才映射）。走与 {@code /chat} 同一套鉴权链
 * （{@code X-Api-Key} + 多租户 + 限流 + 配额）—— 不进 permitAll，任意合法 key 可用。
 */
@RestController
@ConditionalOnProperty(name = "app.voice.enabled", havingValue = "true")
public class VoiceController {

    private final VoiceConversationService voice;
    private final VoiceStreamService voiceStream;
    private final VoiceProperties props;

    public VoiceController(VoiceConversationService voice, VoiceStreamService voiceStream, VoiceProperties props) {
        this.voice = voice;
        this.voiceStream = voiceStream;
        this.props = props;
    }

    /** 完整轮次：音频 → ASR → 客服大脑 → TTS。返回 transcript + reply 文本 + base64 语音。 */
    @PostMapping(value = "/voice/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> chat(@RequestPart("audio") MultipartFile audio,
                                  @RequestParam(required = false) String chatId) throws IOException {
        ResponseEntity<?> bad = validate(audio);
        if (bad != null) return bad;
        String cid = (chatId == null || chatId.isBlank()) ? "voice-" + UUID.randomUUID() : chatId;
        VoiceConversationService.VoiceReply reply =
                voice.chat(audio.getBytes(), audio.getOriginalFilename(), cid);
        return ResponseEntity.ok(reply);
    }

    /**
     * SSE 半流式：音频 → ASR → 流式生成 → 分句 TTS。先发 {@code transcript} 事件，再逐句发
     * {@code audio-chunk}（{@code {text, audioContentType, audioBase64}}），最后 {@code done}。
     * 回复边生成边播，延迟低于 {@code /voice/chat}。只走对话（退款类用 {@code /voice/chat}）。
     */
    @PostMapping(value = "/voice/chat/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestPart("audio") MultipartFile audio,
                                 @RequestParam(required = false) String chatId) throws IOException {
        if (audio == null || audio.isEmpty() || audio.getSize() > props.getMaxAudioBytes()) {
            SseEmitter err = new SseEmitter();
            err.completeWithError(new IllegalArgumentException("empty or oversized audio"));
            return err;
        }
        String cid = (chatId == null || chatId.isBlank()) ? "voice-" + UUID.randomUUID() : chatId;
        return voiceStream.stream(audio.getBytes(), audio.getOriginalFilename(), cid);
    }

    /** 只做 ASR（调试 / 纯转写）。 */
    @PostMapping(value = "/voice/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcribe(@RequestPart("audio") MultipartFile audio) throws IOException {
        ResponseEntity<?> bad = validate(audio);
        if (bad != null) return bad;
        return ResponseEntity.ok(Map.of("transcript", voice.transcribeOnly(audio.getBytes(), audio.getOriginalFilename())));
    }

    private ResponseEntity<?> validate(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty audio"));
        }
        if (audio.getSize() > props.getMaxAudioBytes()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "audio too large: " + audio.getSize() + " > " + props.getMaxAudioBytes() + " bytes"));
        }
        return null;
    }
}
