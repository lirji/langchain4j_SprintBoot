package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.grounding.GroundednessChecker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GroundingConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.rag.grounding")
    public GroundingProperties groundingProperties() {
        return new GroundingProperties();
    }

    /**
     * GroundednessChecker 走独立 temp=0 ChatModel —— 跟 {@code Critic} / {@code Judge} 同思路：
     * 同一 (sources, answer) 多次判定要给一致分数，否则 warn 闸门会假触发。
     *
     * <p>仅在 {@code app.rag.grounding.enabled=true} 时构造，关闭时连 judge model 都不建（零开销）。
     * 由 {@link LlmConfig#buildJudgeChatModel} 程序化构造，<strong>不注册 ChatModel Bean</strong>，
     * 避免和主 chatModel 冲突（详见该方法注释）。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.grounding.enabled", havingValue = "true")
    public GroundednessChecker groundednessChecker(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        ChatModel judgeModel = llmConfig.buildJudgeChatModel(props);
        return AiServices.builder(GroundednessChecker.class)
                .chatModel(judgeModel)
                .build();
    }
}
