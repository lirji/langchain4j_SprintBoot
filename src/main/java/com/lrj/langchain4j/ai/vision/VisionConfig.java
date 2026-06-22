package com.lrj.langchain4j.ai.vision;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 视觉模型装配。<strong>整个 config 条件化在 {@code app.vision.enabled=true}</strong> ——
 * 关闭（默认）时 vision 相关 Bean（含 {@link VisionModel}）全不存在，零开销、零依赖网络。
 *
 * <p>按 {@code app.vision.provider} 选后端：
 * <ul>
 *   <li>{@code openai-compat}（默认）— 走 {@code OpenAiChatModel}，支持云 OpenAI / Azure /
 *       vLLM 等任意 OpenAI 兼容多模态端点（gpt-4o / gpt-4o-mini / qwen2.5-vl…）</li>
 *   <li>{@code ollama} — 走 {@code OllamaChatModel} 本地多模态模型（llava / qwen2.5-vl /
 *       llama3.2-vision），零成本本地开发用</li>
 * </ul>
 *
 * <p><strong>关键</strong>：这里构造的视觉 {@code ChatModel} 只作为 {@link DefaultVisionModel}
 * 的构造入参，<strong>不注册成 Spring Bean</strong>——否则会与 {@code LlmConfig} 的主
 * {@code ChatModel} 撞 {@code @AiService} 自动发现的「只能有一个 ChatModel」约束。
 */
@Configuration
@ConditionalOnProperty(name = "app.vision.enabled", havingValue = "true")
public class VisionConfig {

    private static final Logger log = LoggerFactory.getLogger(VisionConfig.class);

    /**
     * 所有 {@code ChatModelListener} Bean（{@code LoggingChatModelListener} /
     * {@code MetricsChatModelListener} / {@code TokenBudgetChatModelListener}）。
     * <strong>必须灌进视觉 ChatModel</strong>——否则视觉调用既不进 Prometheus 指标、
     * 也不回填 per-tenant 日 token 预算，等于绕过配额（与 {@code LlmConfig} 同样的接法）。
     */
    private final List<ChatModelListener> listeners;

    public VisionConfig(List<ChatModelListener> listeners) {
        this.listeners = listeners;
    }

    @Bean
    @ConfigurationProperties(prefix = "app.vision")
    public VisionProperties visionProperties() {
        return new VisionProperties();
    }

    @Bean
    public VisionModel visionModel(VisionProperties props) {
        ChatModel model = buildVisionChatModel(props);
        log.info("VisionModel ready: provider={} model={} maxImageBytes={} captionCacheSize={}",
                props.getProvider(), props.getModelName(), props.getMaxImageBytes(), props.getCaptionCacheSize());
        return new DefaultVisionModel(model, props.getCaptionPrompt(),
                props.getMaxImageBytes(), props.getCaptionCacheSize());
    }

    /** 注意：返回的 ChatModel 不作为 Bean，仅供 {@link DefaultVisionModel} 内部使用。 */
    private ChatModel buildVisionChatModel(VisionProperties p) {
        String provider = p.getProvider() == null ? "openai-compat" : p.getProvider().trim().toLowerCase();
        Duration timeout = Duration.ofSeconds(p.getTimeoutSeconds());
        return switch (provider) {
            case "openai-compat", "openai" -> {
                if (p.getApiKey() == null || p.getApiKey().isBlank()) {
                    log.warn("app.vision.api-key is blank; cloud OpenAI will 401. "
                            + "Set the key, or point base-url at a local gateway (vLLM/Ollama).");
                }
                OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                        .apiKey(p.getApiKey() == null || p.getApiKey().isBlank() ? "EMPTY" : p.getApiKey())
                        .modelName(p.getModelName())
                        .temperature(p.getTemperature())
                        .timeout(timeout)
                        .maxRetries(p.getMaxRetries())
                        .listeners(listeners)
                        .logRequests(p.isLogRequests())
                        .logResponses(p.isLogResponses());
                if (p.getBaseUrl() != null && !p.getBaseUrl().isBlank()) b.baseUrl(p.getBaseUrl());
                yield b.build();
            }
            case "ollama" -> OllamaChatModel.builder()
                    .baseUrl(p.getBaseUrl() == null || p.getBaseUrl().isBlank()
                            ? "http://localhost:11434" : p.getBaseUrl())
                    .modelName(p.getModelName())
                    .temperature(p.getTemperature())
                    .timeout(timeout)
                    .maxRetries(p.getMaxRetries())
                    .listeners(listeners)
                    .logRequests(p.isLogRequests())
                    .logResponses(p.isLogResponses())
                    .build();
            default -> throw new IllegalArgumentException(
                    "Unknown app.vision.provider: " + provider + " (expected openai-compat|ollama)");
        };
    }
}
