package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.chaining.ChainLink;
import com.lrj.langchain4j.ai.chaining.ChainStep;
import com.lrj.langchain4j.ai.chaining.PromptChainService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt Chaining 装配（Anthropic workflow 模式之一）。<strong>整个 config 条件化在
 * {@code app.chaining.enabled=true}</strong> —— 关闭（默认）时 {@link ChainLink} / {@link PromptChainService}
 * 及 {@code ChainController} 全不装配，零开销。
 *
 * <p>{@link ChainLink} 复用主 {@code ChatModel}（已挂 metrics + per-tenant token 预算 listener），
 * 程序化 {@code AiServices.builder} 构建、不带 ChatMemory（链每步无状态），不新建 ChatModel Bean。
 */
@Configuration
@ConditionalOnProperty(name = "app.chaining.enabled", havingValue = "true")
public class ChainingConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.chaining")
    public ChainProperties chainProperties() {
        return new ChainProperties();
    }

    @Bean
    public ChainLink chainLink(ChatModel chatModel) {
        return AiServices.builder(ChainLink.class).chatModel(chatModel).build();
    }

    @Bean
    public PromptChainService promptChainService(ChainLink link) {
        return new PromptChainService(link);
    }

    /** {@code app.chaining.*} 绑定。{@code steps} 是预定义的默认链（顺序 + 步间 gate）。 */
    public static class ChainProperties {
        private boolean enabled = false;
        /** 默认链的步骤（顺序执行，步间按 gate 校验短路）。可在 yml 定义，也可请求体临时覆盖。 */
        private List<ChainStep> steps = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<ChainStep> getSteps() { return steps; }
        public void setSteps(List<ChainStep> steps) { this.steps = steps; }
    }
}
