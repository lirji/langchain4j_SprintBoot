package com.lrj.langchain4j.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 兼容的语音实现（JDK {@link HttpClient}，零新依赖，跟 {@code FeishuClient} 同款 HTTP 风格）：
 * <ul>
 *   <li>ASR：{@code POST {base-url}/audio/transcriptions}，multipart/form-data（file + model[+ language]）</li>
 *   <li>TTS：{@code POST {base-url}/audio/speech}，JSON（model/voice/input/response_format）→ 音频字节</li>
 * </ul>
 * {@code base-url} 一换即可指云 OpenAI / Azure / 本地 faster-whisper + openedai-speech 网关。
 */
public class OpenAiSpeechService implements SpeechService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSpeechService.class);

    private final VoiceProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiSpeechService(VoiceProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds())))
                .build();
        log.info("OpenAiSpeechService ready: base-url={} asr={} tts={}",
                trimSlash(props.getBaseUrl()), props.getAsrModel(), props.getTtsModel());
    }

    @Override
    public String transcribe(byte[] audio, String filename) {
        String boundary = "----lc4jvoice" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(boundary, audio, filename);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(props.getBaseUrl()) + "/audio/transcriptions"))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("ASR failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            JsonNode node = mapper.readTree(resp.body());
            return node.path("text").asText("");
        } catch (Exception e) {
            throw new RuntimeException("ASR request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Speech synthesize(String text) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("model", props.getTtsModel());
            payload.put("voice", props.getTtsVoice());
            payload.put("input", text);
            payload.put("response_format", props.getTtsFormat());
            String json = mapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(props.getBaseUrl()) + "/audio/speech"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("TTS failed: HTTP " + resp.statusCode()
                        + " " + new String(resp.body(), StandardCharsets.UTF_8));
            }
            return new Speech(resp.body(), props.ttsContentType());
        } catch (Exception e) {
            throw new RuntimeException("TTS request failed: " + e.getMessage(), e);
        }
    }

    /** 手搓 multipart 体：file 部分（二进制） + model 部分（+ 可选 language）。 */
    private byte[] multipartBody(String boundary, byte[] audio, String filename) {
        String safeName = (filename == null || filename.isBlank()) ? "audio.mp3" : filename;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> parts = new ArrayList<>();
        String dash = "--";
        String crlf = "\r\n";

        StringBuilder filePart = new StringBuilder();
        filePart.append(dash).append(boundary).append(crlf)
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(safeName).append("\"").append(crlf)
                .append("Content-Type: application/octet-stream").append(crlf).append(crlf);
        parts.add(filePart.toString().getBytes(StandardCharsets.UTF_8));
        parts.add(audio);
        parts.add(crlf.getBytes(StandardCharsets.UTF_8));

        parts.add(field(boundary, "model", props.getAsrModel()));
        if (props.getLanguage() != null && !props.getLanguage().isBlank()) {
            parts.add(field(boundary, "language", props.getLanguage()));
        }
        parts.add((dash + boundary + dash + crlf).getBytes(StandardCharsets.UTF_8));

        try {
            for (byte[] p : parts) out.write(p);
        } catch (Exception e) {
            throw new RuntimeException("failed to assemble multipart body", e);
        }
        return out.toByteArray();
    }

    private static byte[] field(String boundary, String name, String value) {
        String s = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
