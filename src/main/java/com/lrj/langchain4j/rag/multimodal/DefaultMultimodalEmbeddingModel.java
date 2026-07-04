package com.lrj.langchain4j.rag.multimodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MultimodalEmbeddingModel} 的默认实现：走 OpenAI 兼容的 {@code POST {base-url}/embeddings}
 * 端点，用 JDK {@link HttpClient} 手搓请求（<strong>零新依赖</strong>，跟 {@code voice/OpenAiSpeechService}
 * 同款 HTTP 风格）。后端可以是 vLLM / TEI 托管的 CLIP / jina-clip，或云 jina embeddings。
 *
 * <p>请求体（文本）：{@code {"model":..., "input":["<text>"], "encoding_format":"float"}}。<br>
 * 请求体（图片）：{@code {"model":..., "input":[{"image":"<data-uri 或 base64>"}]}}——
 * {@code input} 元素用对象携带 {@code image} 字段是 jina-clip 的多模态约定；纯文本仍用字符串元素。
 * 具体图片承载形式由 {@code app.rag.multimodal-embedding.image-input-format} 控制。<br>
 * 响应解析：{@code data[0].embedding} → {@code float[]}。
 *
 * <p><strong>可测</strong>：真正发 HTTP 的动作收敛到 {@link #post(String)} 一个方法，单测里子类覆盖它
 * 返回预置 JSON，即可在不连网的情况下验证「请求体拼装 + 响应解析 + 维度校验」。
 */
public class DefaultMultimodalEmbeddingModel implements MultimodalEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DefaultMultimodalEmbeddingModel.class);

    private final MultimodalEmbeddingProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public DefaultMultimodalEmbeddingModel(MultimodalEmbeddingProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds())))
                .build();
        log.info("DefaultMultimodalEmbeddingModel ready: base-url={} model={} dim={} imageInputFormat={}",
                trimSlash(props.getBaseUrl()), props.getModelName(), props.getDimension(), props.getImageInputFormat());
    }

    @Override
    public float[] embedText(String text) {
        if (text == null) text = "";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getModelName());
        payload.put("input", List.of(text));
        payload.put("encoding_format", "float");
        return requestEmbedding(payload);
    }

    @Override
    public float[] embedImage(byte[] image, String mimeType) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (image.length > props.getMaxImageBytes()) {
            throw new IllegalArgumentException(
                    "image too large: " + image.length + " > " + props.getMaxImageBytes() + " bytes");
        }
        String base64 = Base64.getEncoder().encodeToString(image);
        String mime = normalizeMime(mimeType);
        String imageValue = "data-uri".equalsIgnoreCase(props.getImageInputFormat())
                ? "data:" + mime + ";base64," + base64
                : base64;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("image", imageValue);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getModelName());
        payload.put("input", List.of(item));
        payload.put("encoding_format", "float");
        return requestEmbedding(payload);
    }

    @Override
    public int dimension() {
        return props.getDimension();
    }

    /** 组装 JSON → POST → 解析 {@code data[0].embedding} → 维度校验。 */
    private float[] requestEmbedding(Map<String, Object> payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize embedding request: " + e.getMessage(), e);
        }
        if (props.isLogRequests()) {
            log.info("multimodal embedding request: {}", json.length() > 200 ? json.substring(0, 200) + "…" : json);
        }
        String body = post(json);
        return parseEmbedding(body);
    }

    /**
     * 真正发 HTTP 的唯一出口（带手动退避重试）。<strong>单测覆盖此方法即可脱网</strong>。
     *
     * @param jsonBody 已序列化的请求体
     * @return 响应体字符串
     */
    protected String post(String jsonBody) {
        RuntimeException last = null;
        int attempts = Math.max(1, props.getMaxRetries() + 1);
        for (int i = 0; i < attempts; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(trimSlash(props.getBaseUrl()) + "/embeddings"))
                        .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                        .header("Authorization", "Bearer "
                                + (props.getApiKey() == null || props.getApiKey().isBlank() ? "EMPTY" : props.getApiKey()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "multimodal embedding failed: HTTP " + resp.statusCode() + " " + resp.body());
                }
                return resp.body();
            } catch (Exception e) {
                last = (e instanceof RuntimeException re) ? re
                        : new RuntimeException("multimodal embedding request failed: " + e.getMessage(), e);
                log.warn("multimodal embedding attempt {}/{} failed: {}", i + 1, attempts, e.getMessage());
            }
        }
        throw last != null ? last : new RuntimeException("multimodal embedding request failed");
    }

    /** 解析 OpenAI 兼容响应 {@code data[0].embedding} → float[]，并做可选维度校验。 */
    private float[] parseEmbedding(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode emb = root.path("data").path(0).path("embedding");
            if (!emb.isArray() || emb.isEmpty()) {
                throw new IllegalStateException("no embedding in response: "
                        + (body.length() > 300 ? body.substring(0, 300) + "…" : body));
            }
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vec[i] = (float) emb.get(i).asDouble();
            }
            if (props.getDimension() > 0 && vec.length != props.getDimension()) {
                log.warn("multimodal embedding dimension mismatch: got {} but app.rag.multimodal-embedding.dimension={} "
                        + "(persistent stores keyed on the configured dim may reject this)",
                        vec.length, props.getDimension());
            }
            return vec;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("failed to parse embedding response: " + e.getMessage(), e);
        }
    }

    /** 缺失/非 image/* 的 MIME 兜底成 image/png。 */
    private static String normalizeMime(String mimeType) {
        return (mimeType != null && mimeType.startsWith("image/")) ? mimeType : "image/png";
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
