package com.lrj.langchain4j.ai.cascade;

import com.lrj.langchain4j.config.LlmConfig;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Model Cascade / Cost Routing 装配。<strong>整个 config 条件化在 {@code app.llm.cascade.enabled=true}</strong>
 * —— 关闭（默认）时便宜/强模型、{@link ConfidenceGate}、{@link CascadeService}、{@code CascadeController}
 * 全不装配，零开销。
 *
 * <p>关键约束：便宜模型 + 强模型 + 自评模型都通过 {@link LlmConfig#buildJudgeChatModel} 程序化构建，
 * <strong>不注册成第二个 {@code ChatModel} Bean</strong>（LangChain4j {@code @AiService} 自动发现
 * 见到 &gt;1 个 ChatModel Bean 就抛 conflict）。做法：以当前 provider 的配置为底，复制一份并把
 * model-name 换成 {@code cheap-model} / {@code strong-model} 再构建 —— 换 provider 时级联自动跟随。
 *
 * <p>{@code buildJudgeChatModel} 是 temp=0 确定性构建，对成本路由是可接受甚至可取的（同问同答、
 * 可复现）。要非零温度需在 {@code LlmConfig} 暴露对应构建入口（本模块不改共享文件）。
 */
@Configuration
@ConditionalOnProperty(name = "app.llm.cascade.enabled", havingValue = "true")
public class CascadeConfig {

    private static final Logger log = LoggerFactory.getLogger(CascadeConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "app.llm.cascade")
    public CascadeProperties cascadeProperties() {
        return new CascadeProperties();
    }

    @Bean
    public ConfidenceGate cascadeConfidenceGate(CascadeProperties props,
                                                LlmConfig llmConfig,
                                                LlmConfig.LlmProperties base) {
        // 自评模型：用便宜模型自评（省钱，且被评的就是它的答案）。未开自评时不构建。
        ChatModel rater = props.isSelfRating()
                ? llmConfig.buildJudgeChatModel(withModel(base, props.getCheapModel()))
                : null;
        return new ConfidenceGate(props, rater);
    }

    /**
     * <strong>CascadeChatModel 故意不注册成 Bean</strong>：它 implements {@link ChatModel}，一旦成为
     * Spring Bean，容器里就有 2 个 ChatModel 类型的 Bean（主 {@code chatModel} + 它），
     * {@code langchain4j-spring} 的 {@code AiServicesAutoConfig} 按 {@code getBeanNamesForType(ChatModel.class)}
     * 枚举、数量 &gt;1 直接抛 {@code IllegalConfigurationException(conflict)} —— 整个 {@code @AiService}
     * （Assistant）装配崩掉。所以把 cheap/strong/cascade 全 {@code new} 在这个 {@link CascadeService} Bean
     * 内部，只暴露 {@code CascadeService}（非 ChatModel 类型），级联模型作为其私有字段存在。
     * 这与 {@code VisionConfig} / {@code AgentBrain} 「私有模型不进容器」是同一套路。
     */
    @Bean
    public CascadeService cascadeService(CascadeProperties props,
                                         ConfidenceGate gate,
                                         LlmConfig llmConfig,
                                         LlmConfig.LlmProperties base,
                                         MeterRegistry registry) {
        ChatModel cheap = llmConfig.buildJudgeChatModel(withModel(base, props.getCheapModel()));
        ChatModel strong = llmConfig.buildJudgeChatModel(withModel(base, props.getStrongModel()));
        log.info("Model cascade enabled: provider={}, cheap-model={}, strong-model={}, self-rating={}",
                base.getProvider(),
                orDefault(props.getCheapModel(), "<provider default>"),
                orDefault(props.getStrongModel(), "<provider default>"),
                props.isSelfRating());
        CascadeChatModel cascade = new CascadeChatModel(cheap, strong, gate, registry);
        return new CascadeService(cascade);
    }

    // ---------- 构建「换了 model-name 的 LlmProperties 副本」 ----------

    /**
     * 以 {@code base} 为底复制一份 {@link LlmConfig.LlmProperties}，把**当前 provider** 的 model-name
     * 换成 {@code modelName}（为空则保留原值）。只复制当前 provider 的 sub-props（其余 provider
     * {@code buildJudgeChatModel} 用不到，留默认即可），不改共享的 base Bean。
     */
    private static LlmConfig.LlmProperties withModel(LlmConfig.LlmProperties base, String modelName) {
        LlmConfig.LlmProperties p = new LlmConfig.LlmProperties();
        String provider = base.getProvider();
        p.setProvider(provider);
        switch (normalize(provider)) {
            case "openai" -> p.setOpenai(copyOpenAi(base.getOpenai(), modelName));
            case "deepseek" -> p.setDeepseek(copyOpenAi(base.getDeepseek(), modelName));
            case "vllm" -> p.setVllm(copyOpenAi(base.getVllm(), modelName));
            case "anthropic" -> p.setAnthropic(copyAnthropic(base.getAnthropic(), modelName));
            case "gemini" -> p.setGemini(copyGemini(base.getGemini(), modelName));
            default -> p.setOllama(copyOllama(base.getOllama(), modelName));
        }
        return p;
    }

    private static LlmConfig.OllamaProps copyOllama(LlmConfig.OllamaProps s, String modelName) {
        LlmConfig.OllamaProps d = new LlmConfig.OllamaProps();
        d.setBaseUrl(s.getBaseUrl());
        d.setModelName(pick(modelName, s.getModelName()));
        d.setTemperature(s.getTemperature());
        d.setTimeout(s.getTimeout());
        d.setMaxRetries(s.getMaxRetries());
        d.setLogRequests(s.isLogRequests());
        d.setLogResponses(s.isLogResponses());
        return d;
    }

    private static LlmConfig.OpenAiCompatProps copyOpenAi(LlmConfig.OpenAiCompatProps s, String modelName) {
        LlmConfig.OpenAiCompatProps d = new LlmConfig.OpenAiCompatProps();
        d.setBaseUrl(s.getBaseUrl());
        d.setApiKey(s.getApiKey());
        d.setModelName(pick(modelName, s.getModelName()));
        d.setTemperature(s.getTemperature());
        d.setTimeout(s.getTimeout());
        d.setMaxRetries(s.getMaxRetries());
        d.setLogRequests(s.isLogRequests());
        d.setLogResponses(s.isLogResponses());
        return d;
    }

    private static LlmConfig.AnthropicProps copyAnthropic(LlmConfig.AnthropicProps s, String modelName) {
        LlmConfig.AnthropicProps d = new LlmConfig.AnthropicProps();
        d.setApiKey(s.getApiKey());
        d.setModelName(pick(modelName, s.getModelName()));
        d.setTemperature(s.getTemperature());
        d.setTimeout(s.getTimeout());
        d.setMaxRetries(s.getMaxRetries());
        d.setLogRequests(s.isLogRequests());
        d.setLogResponses(s.isLogResponses());
        d.setCacheSystemMessages(s.isCacheSystemMessages());
        d.setCacheTools(s.isCacheTools());
        return d;
    }

    private static LlmConfig.GeminiProps copyGemini(LlmConfig.GeminiProps s, String modelName) {
        LlmConfig.GeminiProps d = new LlmConfig.GeminiProps();
        d.setApiKey(s.getApiKey());
        d.setModelName(pick(modelName, s.getModelName()));
        d.setTemperature(s.getTemperature());
        d.setTimeout(s.getTimeout());
        d.setMaxRetries(s.getMaxRetries());
        d.setLogRequests(s.isLogRequests());
        d.setLogResponses(s.isLogResponses());
        return d;
    }

    private static String pick(String override, String fallback) {
        return (override != null && !override.isBlank()) ? override : fallback;
    }

    private static String orDefault(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static String normalize(String s) {
        return s == null ? "ollama" : s.trim().toLowerCase();
    }
}
