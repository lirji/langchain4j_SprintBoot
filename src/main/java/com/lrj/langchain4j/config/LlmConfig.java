package com.lrj.langchain4j.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;

/**
 * 单一开关 {@code app.llm.provider} 决定本进程使用哪一家 ChatModel/StreamingChatModel。
 *
 * <p>可选值：{@code ollama | openai | anthropic | gemini | deepseek}。
 * 各 provider 的具体配置在 {@code app.llm.<provider>.*} 下；
 * 未被选中的 provider 配置即使存在也不会创建 Bean。
 *
 * <p>注意：EmbeddingModel 仍由 {@code langchain4j-ollama-spring-boot-starter}
 * 通过 {@code langchain4j.ollama.embedding-model.*} 自动装配，
 * 切换 chat provider 不会影响 RAG 向量库（向量维度由 embedding 模型决定）。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "app.llm")
    public LlmProperties llmProperties() {
        return new LlmProperties();
    }

    /**
     * 主 ChatModel：所有业务 AiService（Assistant / Critic / Planner / Worker / Synthesizer /
     * Extractor / Answerer）默认注入这个。
     */
    @Bean
    public ChatModel chatModel(LlmProperties props) {
        return buildChat(props, null);
    }

    /**
     * Judge 专用 ChatModel：固定 temperature=0。
     * <strong>不注册成 Bean</strong> —— LangChain4j 的 {@code @AiService} 自动发现按
     * {@code getBeanNamesForType(ChatModel.class)} 枚举，多于 1 个就抛 conflict
     * （{@code @Primary} 和 {@code autowireCandidate=false} 都不顶用）。所以 Judge 的 model
     * 通过 {@link com.lrj.langchain4j.config.EvalConfig} 直接调用本方法构造，绕开 Spring 注入。
     */
    public ChatModel buildJudgeChatModel(LlmProperties props) {
        log.info("Building Judge ChatModel: provider={}, temperature=0.0 (deterministic)", props.getProvider());
        return buildChat(props, 0.0);
    }

    /** {@code tempOverride==null} 时用 props 里配的温度；否则用传入值（Judge 专用 0.0）。 */
    private ChatModel buildChat(LlmProperties props, Double tempOverride) {
        String provider = normalize(props.getProvider());
        if (tempOverride == null) {
            log.info("ChatModel provider = {}", provider);
        }
        return switch (provider) {
            case "openai" -> buildOpenAiChat(props.getOpenai(), "openai", tempOverride);
            case "deepseek" -> buildOpenAiChat(props.getDeepseek(), "deepseek", tempOverride);
            case "anthropic" -> buildAnthropicChat(props.getAnthropic(), tempOverride);
            case "gemini" -> buildGeminiChat(props.getGemini(), tempOverride);
            case "ollama" -> buildOllamaChat(props.getOllama(), tempOverride);
            default -> throw new IllegalArgumentException(
                    "Unknown app.llm.provider: " + provider + " (expected ollama|openai|anthropic|gemini|deepseek)");
        };
    }

    @Bean
    public EmbeddingModel embeddingModel(LlmProperties props) {
        OllamaProps p = props.getOllama();
        log.info("EmbeddingModel: ollama {} @ {}", p.getEmbeddingModelName(), p.getBaseUrl());
        return OllamaEmbeddingModel.builder()
                .baseUrl(p.getBaseUrl())
                .modelName(p.getEmbeddingModelName())
                .timeout(p.getTimeout())
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel(LlmProperties props) {
        String provider = normalize(props.getProvider());
        log.info("StreamingChatModel provider = {}", provider);
        return switch (provider) {
            case "openai" -> buildOpenAiStream(props.getOpenai(), "openai");
            case "deepseek" -> buildOpenAiStream(props.getDeepseek(), "deepseek");
            case "anthropic" -> buildAnthropicStream(props.getAnthropic());
            case "gemini" -> buildGeminiStream(props.getGemini());
            case "ollama" -> buildOllamaStream(props.getOllama());
            default -> throw new IllegalArgumentException(
                    "Unknown app.llm.provider: " + provider + " (expected ollama|openai|anthropic|gemini|deepseek)");
        };
    }

    // -------- builders --------

    private OpenAiChatModel buildOpenAiChat(OpenAiCompatProps p, String kind, Double tempOverride) {
        require(p.getApiKey(), "app.llm." + kind + ".api-key");
        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(tempOverride != null ? tempOverride : p.getTemperature())
                .timeout(p.getTimeout())
                .logRequests(p.isLogRequests())
                .logResponses(p.isLogResponses());
        if (notBlank(p.getBaseUrl())) b.baseUrl(p.getBaseUrl());
        return b.build();
    }

    private OpenAiStreamingChatModel buildOpenAiStream(OpenAiCompatProps p, String kind) {
        require(p.getApiKey(), "app.llm." + kind + ".api-key");
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder b = OpenAiStreamingChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(p.getTemperature())
                .timeout(p.getTimeout());
        if (notBlank(p.getBaseUrl())) b.baseUrl(p.getBaseUrl());
        return b.build();
    }

    private AnthropicChatModel buildAnthropicChat(AnthropicProps p, Double tempOverride) {
        require(p.getApiKey(), "app.llm.anthropic.api-key");
        return AnthropicChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(tempOverride != null ? tempOverride : p.getTemperature())
                .timeout(p.getTimeout())
                .logRequests(p.isLogRequests())
                .logResponses(p.isLogResponses())
                .build();
    }

    private AnthropicStreamingChatModel buildAnthropicStream(AnthropicProps p) {
        require(p.getApiKey(), "app.llm.anthropic.api-key");
        return AnthropicStreamingChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(p.getTemperature())
                .timeout(p.getTimeout())
                .build();
    }

    private GoogleAiGeminiChatModel buildGeminiChat(GeminiProps p, Double tempOverride) {
        require(p.getApiKey(), "app.llm.gemini.api-key");
        return GoogleAiGeminiChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(tempOverride != null ? tempOverride : p.getTemperature())
                .timeout(p.getTimeout())
                .logRequestsAndResponses(p.isLogRequests() || p.isLogResponses())
                .build();
    }

    private GoogleAiGeminiStreamingChatModel buildGeminiStream(GeminiProps p) {
        require(p.getApiKey(), "app.llm.gemini.api-key");
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(p.getTemperature())
                .timeout(p.getTimeout())
                .build();
    }

    private OllamaChatModel buildOllamaChat(OllamaProps p, Double tempOverride) {
        return OllamaChatModel.builder()
                .baseUrl(p.getBaseUrl())
                .modelName(p.getModelName())
                .temperature(tempOverride != null ? tempOverride : p.getTemperature())
                .timeout(p.getTimeout())
                .logRequests(p.isLogRequests())
                .logResponses(p.isLogResponses())
                .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                .build();
    }

    private OllamaStreamingChatModel buildOllamaStream(OllamaProps p) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(p.getBaseUrl())
                .modelName(p.getModelName())
                .temperature(p.getTemperature())
                .timeout(p.getTimeout())
                .build();
    }

    // -------- helpers --------

    private static String normalize(String s) {
        return s == null ? "ollama" : s.trim().toLowerCase();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " is required for the active provider (set it via env var or yml)");
        }
    }

    // -------- properties --------

    public static class LlmProperties {
        private String provider = "ollama";
        private OllamaProps ollama = new OllamaProps();
        private OpenAiCompatProps openai = OpenAiCompatProps.openaiDefaults();
        private OpenAiCompatProps deepseek = OpenAiCompatProps.deepseekDefaults();
        private AnthropicProps anthropic = new AnthropicProps();
        private GeminiProps gemini = new GeminiProps();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public OllamaProps getOllama() { return ollama; }
        public void setOllama(OllamaProps ollama) { this.ollama = ollama; }
        public OpenAiCompatProps getOpenai() { return openai; }
        public void setOpenai(OpenAiCompatProps openai) { this.openai = openai; }
        public OpenAiCompatProps getDeepseek() { return deepseek; }
        public void setDeepseek(OpenAiCompatProps deepseek) { this.deepseek = deepseek; }
        public AnthropicProps getAnthropic() { return anthropic; }
        public void setAnthropic(AnthropicProps anthropic) { this.anthropic = anthropic; }
        public GeminiProps getGemini() { return gemini; }
        public void setGemini(GeminiProps gemini) { this.gemini = gemini; }
    }

    public static class OllamaProps {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "llama3.1";
        /** RAG 用的 embedding 模型，base-url/timeout 与 chat 复用。 */
        private String embeddingModelName = "nomic-embed-text";
        private Double temperature = 0.7;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean logRequests = true;
        private boolean logResponses = true;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getEmbeddingModelName() { return embeddingModelName; }
        public void setEmbeddingModelName(String embeddingModelName) { this.embeddingModelName = embeddingModelName; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public boolean isLogRequests() { return logRequests; }
        public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
        public boolean isLogResponses() { return logResponses; }
        public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    }

    /** OpenAI 协议族（OpenAI 官方 / DeepSeek / 任意 OpenAI-compatible 服务）共用配置 */
    public static class OpenAiCompatProps {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature = 0.7;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean logRequests = false;
        private boolean logResponses = false;

        static OpenAiCompatProps openaiDefaults() {
            OpenAiCompatProps p = new OpenAiCompatProps();
            p.modelName = "gpt-4o-mini";
            return p;
        }

        static OpenAiCompatProps deepseekDefaults() {
            OpenAiCompatProps p = new OpenAiCompatProps();
            p.baseUrl = "https://api.deepseek.com/v1";
            p.modelName = "deepseek-chat";
            return p;
        }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public boolean isLogRequests() { return logRequests; }
        public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
        public boolean isLogResponses() { return logResponses; }
        public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    }

    public static class AnthropicProps {
        private String apiKey;
        private String modelName = "claude-haiku-4-5";
        private Double temperature = 0.7;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean logRequests = false;
        private boolean logResponses = false;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public boolean isLogRequests() { return logRequests; }
        public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
        public boolean isLogResponses() { return logResponses; }
        public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    }

    public static class GeminiProps {
        private String apiKey;
        private String modelName = "gemini-2.0-flash";
        private Double temperature = 0.7;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean logRequests = false;
        private boolean logResponses = false;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public boolean isLogRequests() { return logRequests; }
        public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
        public boolean isLogResponses() { return logResponses; }
        public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    }
}
