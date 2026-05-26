package com.lrj.langchain4j.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * EmbeddingModel 单一开关 {@code app.embedding.provider} 决定走哪家：
 * <ul>
 *   <li>{@code ollama}（默认，本地开发用）— 走 {@code OllamaEmbeddingModel}，默认 {@code nomic-embed-text}（768 维）</li>
 *   <li>{@code openai-compat}（推荐生产用）— 走 {@code OpenAiEmbeddingModel}，支持任何 OpenAI-compat 的 embedding 服务，
 *       比如 vLLM 跑 {@code BAAI/bge-m3}（1024 维）/ TEI / 云端 OpenAI 等</li>
 * </ul>
 *
 * <p><strong>切换 embedding 模型 = 切换向量维度 = 必须重建向量库</strong>。
 * InMemoryEmbeddingStore 重启即丢，无所谓；PGVector / Milvus 等持久化存储已有的表/集合
 * 维度对不上会插入失败。换 embedding 前先 drop 重建 + 重新 ingest。
 *
 * <p>跟 chat provider（{@code app.llm.provider}）完全独立 —— 可以 chat 走 vLLM、embedding 走 Ollama，
 * 或反过来。两边没耦合。
 */
@Configuration
public class EmbeddingModelConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "app.embedding")
    public EmbeddingProperties embeddingProperties() {
        return new EmbeddingProperties();
    }

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingProperties props) {
        String provider = normalize(props.getProvider());
        log.info("EmbeddingModel provider = {}", provider);
        return switch (provider) {
            case "ollama" -> buildOllama(props.getOllama());
            case "openai-compat" -> buildOpenAiCompat(props.getOpenaiCompat());
            default -> throw new IllegalArgumentException(
                    "Unknown app.embedding.provider: " + provider + " (expected ollama|openai-compat)");
        };
    }

    private OllamaEmbeddingModel buildOllama(OllamaEmbeddingProps p) {
        log.info("Embedding: ollama {} @ {} (maxRetries={})", p.getModelName(), p.getBaseUrl(), p.getMaxRetries());
        return OllamaEmbeddingModel.builder()
                .baseUrl(p.getBaseUrl())
                .modelName(p.getModelName())
                .timeout(p.getTimeout())
                .maxRetries(p.getMaxRetries())
                .build();
    }

    private OpenAiEmbeddingModel buildOpenAiCompat(OpenAiCompatEmbeddingProps p) {
        if (p.getApiKey() == null || p.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "app.embedding.openai-compat.api-key is required (vLLM 默认无校验，给 'EMPTY' 占位即可)");
        }
        log.info("Embedding: openai-compat {} @ {} (maxRetries={})", p.getModelName(), p.getBaseUrl(), p.getMaxRetries());
        var b = OpenAiEmbeddingModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .timeout(p.getTimeout())
                .maxRetries(p.getMaxRetries())
                .logRequests(p.isLogRequests())
                .logResponses(p.isLogResponses());
        if (p.getBaseUrl() != null && !p.getBaseUrl().isBlank()) b.baseUrl(p.getBaseUrl());
        return b.build();
    }

    private static String normalize(String s) {
        return s == null ? "ollama" : s.trim().toLowerCase();
    }

    // -------- properties --------

    public static class EmbeddingProperties {
        private String provider = "ollama";
        private OllamaEmbeddingProps ollama = OllamaEmbeddingProps.defaults();
        private OpenAiCompatEmbeddingProps openaiCompat = new OpenAiCompatEmbeddingProps();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public OllamaEmbeddingProps getOllama() { return ollama; }
        public void setOllama(OllamaEmbeddingProps ollama) { this.ollama = ollama; }
        public OpenAiCompatEmbeddingProps getOpenaiCompat() { return openaiCompat; }
        public void setOpenaiCompat(OpenAiCompatEmbeddingProps openaiCompat) { this.openaiCompat = openaiCompat; }
    }

    public static class OllamaEmbeddingProps {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "nomic-embed-text";
        private Duration timeout = Duration.ofSeconds(60);
        private int maxRetries = 3;

        static OllamaEmbeddingProps defaults() {
            return new OllamaEmbeddingProps();
        }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    /**
     * OpenAI-compatible embedding 配置。生产推荐：
     * vLLM 跑 BAAI/bge-m3 → base-url=http://vllm-embed.{ns}.svc.cluster.local:8000/v1,
     * model-name=BAAI/bge-m3 (1024 维多语言)。
     */
    public static class OpenAiCompatEmbeddingProps {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Duration timeout = Duration.ofSeconds(60);
        private int maxRetries = 3;
        private boolean logRequests = false;
        private boolean logResponses = false;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public boolean isLogRequests() { return logRequests; }
        public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
        public boolean isLogResponses() { return logResponses; }
        public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    }
}
