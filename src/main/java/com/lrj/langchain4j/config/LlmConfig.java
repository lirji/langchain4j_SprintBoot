package com.lrj.langchain4j.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 单一开关 {@code app.llm.provider} 决定本进程使用哪一家 ChatModel/StreamingChatModel。
 *
 * <p>可选值：{@code ollama | openai | anthropic | gemini | deepseek | vllm}。
 * 各 provider 的具体配置在 {@code app.llm.<provider>.*} 下；
 * 未被选中的 provider 配置即使存在也不会创建 Bean。
 *
 * <p>{@code vllm} 复用 OpenAI-compat 协议（vLLM 默认就暴露 OpenAI 兼容 API），
 * 推荐用于生产 —— PagedAttention + continuous batching 比 Ollama 吞吐高得多。
 * 默认 base-url 是 K8s 集群内 service DNS 格式占位。
 *
 * <p>EmbeddingModel 由独立的 {@code EmbeddingModelConfig} 装配（{@code app.embedding.provider}），
 * 不再硬绑 Ollama。生产用 vLLM 时建议 embedding 也走 OpenAI-compat（vLLM 跑 embed 模型 / TEI 等）。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /**
     * 所有 {@code ChatModelListener} Bean（{@code LoggingChatModelListener} +
     * {@code MetricsChatModelListener}），Spring 自动按类型注入整列。
     *
     * <p>之前 {@code ObservabilityConfig} 的注释说 "starter scans and wires" —— 但我们手动
     * 在 LlmConfig 里建 ChatModel 绕开了 starter，listener 没挂上，metrics 实际不工作。
     * 修法：手动 `.listeners(listeners)` 灌每个 chat builder。
     */
    private final List<ChatModelListener> listeners;

    public LlmConfig(List<ChatModelListener> listeners) {
        this.listeners = listeners;
    }

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
     * Judge 专用 ChatModel：固定 temperature=0。（控制输出的随机性，0 表示几乎确定性输出 —— 模型总是选择概率最高的 token，结果最稳定、最可预测。值越高（如 0.7、1.0）输出越多样、越有创造性。）
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
            case "vllm" -> buildOpenAiChat(props.getVllm(), "vllm", tempOverride);
            case "anthropic" -> buildAnthropicChat(props.getAnthropic(), tempOverride);
            case "gemini" -> buildGeminiChat(props.getGemini(), tempOverride);
            case "ollama" -> buildOllamaChat(props.getOllama(), tempOverride);
            default -> throw new IllegalArgumentException(
                    "Unknown app.llm.provider: " + provider + " (expected ollama|openai|anthropic|gemini|deepseek|vllm)");
        };
    }

    @Bean
    public StreamingChatModel streamingChatModel(LlmProperties props) {
        String provider = normalize(props.getProvider());
        log.info("StreamingChatModel provider = {}", provider);
        return switch (provider) {
            case "openai" -> buildOpenAiStream(props.getOpenai(), "openai");
            case "deepseek" -> buildOpenAiStream(props.getDeepseek(), "deepseek");
            case "vllm" -> buildOpenAiStream(props.getVllm(), "vllm");
            case "anthropic" -> buildAnthropicStream(props.getAnthropic());
            case "gemini" -> buildGeminiStream(props.getGemini());
            case "ollama" -> buildOllamaStream(props.getOllama());
            default -> throw new IllegalArgumentException(
                    "Unknown app.llm.provider: " + provider + " (expected ollama|openai|anthropic|gemini|deepseek|vllm)");
        };
    }

    // -------- builders --------

    /**
     * OpenAiChat
     *
     * @param p
     * @param kind
     * @param tempOverride
     * @return
     */
    private OpenAiChatModel buildOpenAiChat(OpenAiCompatProps p, String kind, Double tempOverride) {
        require(p.getApiKey(), "app.llm." + kind + ".api-key");
        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(tempOverride != null ? tempOverride : p.getTemperature())
                .timeout(p.getTimeout())
                .maxRetries(p.getMaxRetries())
                .listeners(listeners)
                .logRequests(p.isLogRequests())
                .logResponses(p.isLogResponses());
        if (notBlank(p.getBaseUrl())) b.baseUrl(p.getBaseUrl());
        return b.build();
    }

    /**
     * OpenAiStream
     *
     * @param p
     * @param kind
     * @return
     */
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

    /**
     * AnthropicChat
     *
     * @param p
     * @param tempOverride
     * @return
     */
    private AnthropicChatModel buildAnthropicChat(AnthropicProps p, Double tempOverride) {
        require(p.getApiKey(), "app.llm.anthropic.api-key");
        return AnthropicChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(tempOverride != null ? tempOverride : p.getTemperature())
                .timeout(p.getTimeout())
                .maxRetries(p.getMaxRetries())
                .listeners(listeners)
                .logRequests(p.isLogRequests())
                .logResponses(p.isLogResponses())
                .build();
    }

    /**
     * AnthropicStream
     *
     * @param p
     * @return
     */
    private AnthropicStreamingChatModel buildAnthropicStream(AnthropicProps p) {
        require(p.getApiKey(), "app.llm.anthropic.api-key");
        return AnthropicStreamingChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(p.getTemperature())
                .timeout(p.getTimeout())
                .build();
    }

    /**
     * GeminiChat
     *
     * @param p
     * @param tempOverride
     * @return
     */
    private GoogleAiGeminiChatModel buildGeminiChat(GeminiProps p, Double tempOverride) {
        require(p.getApiKey(), "app.llm.gemini.api-key");
        return GoogleAiGeminiChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(tempOverride != null ? tempOverride : p.getTemperature())
                .timeout(p.getTimeout())
                .maxRetries(p.getMaxRetries())
                .listeners(listeners)
                .logRequestsAndResponses(p.isLogRequests() || p.isLogResponses())
                .build();
    }

    /**
     * GeminiStream
     *
     * @param p
     * @return
     */
    private GoogleAiGeminiStreamingChatModel buildGeminiStream(GeminiProps p) {
        require(p.getApiKey(), "app.llm.gemini.api-key");
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(p.getApiKey())
                .modelName(p.getModelName())
                .temperature(p.getTemperature())
                .timeout(p.getTimeout())
                .build();
    }

    /**
     * OllamaChat
     *
     * @param p
     * @param tempOverride
     * @return
     */
    private OllamaChatModel buildOllamaChat(OllamaProps p, Double tempOverride) {
        return OllamaChatModel.builder()
                .baseUrl(p.getBaseUrl())
                .modelName(p.getModelName())
                .temperature(tempOverride != null ? tempOverride : p.getTemperature())
                .timeout(p.getTimeout())
                .maxRetries(p.getMaxRetries())
                .listeners(listeners)
                .logRequests(p.isLogRequests())
                .logResponses(p.isLogResponses())
                .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                .build();
    }

    /**
     * OllamaStream
     *
     * @param p
     * @return
     */
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
        private OpenAiCompatProps vllm = OpenAiCompatProps.vllmDefaults();
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
        public OpenAiCompatProps getVllm() { return vllm; }
        public void setVllm(OpenAiCompatProps vllm) { this.vllm = vllm; }
        public AnthropicProps getAnthropic() { return anthropic; }
        public void setAnthropic(AnthropicProps anthropic) { this.anthropic = anthropic; }
        public GeminiProps getGemini() { return gemini; }
        public void setGemini(GeminiProps gemini) { this.gemini = gemini; }
    }

    public static class OllamaProps {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "llama3.1";
        private Double temperature = 0.7;
        private Duration timeout = Duration.ofSeconds(60);
        /** 失败自动重试次数（针对网络抖动 / 5xx / 超时）。0 = 关闭。 */
        private int maxRetries = 3;
        private boolean logRequests = true;
        private boolean logResponses = true;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public boolean isLogRequests() { return logRequests; }
        public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
        public boolean isLogResponses() { return logResponses; }
        public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    }

    /** OpenAI 协议族（OpenAI 官方 / DeepSeek / vLLM / 任意 OpenAI-compatible 服务）共用配置 */
    public static class OpenAiCompatProps {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature = 0.7;
        private Duration timeout = Duration.ofSeconds(60);
        /** 失败自动重试次数（429 限流 / 5xx / 超时）。生产推荐 3。 */
        private int maxRetries = 3;
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

        /**
         * vLLM 默认值。base-url 用 K8s 集群内 service DNS 格式占位 ——
         * 实际部署改成 {@code http://<svc-name>.<namespace>.svc.cluster.local:8000/v1}。
         * vLLM 默认不校验 api-key，给非空占位 "EMPTY" 避免被 {@code require()} 拒绝。
         * modelName 留空，强制按部署的实际模型在 yml 里覆盖（vLLM 启动时 --model 那个）。
         */
        static OpenAiCompatProps vllmDefaults() {
            OpenAiCompatProps p = new OpenAiCompatProps();
            p.baseUrl = "http://vllm-chat.default.svc.cluster.local:8000/v1";
            p.apiKey = "EMPTY";
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
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
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
        private int maxRetries = 3;
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
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
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
        private int maxRetries = 3;
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
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public boolean isLogRequests() { return logRequests; }
        public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
        public boolean isLogResponses() { return logResponses; }
        public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    }
}
