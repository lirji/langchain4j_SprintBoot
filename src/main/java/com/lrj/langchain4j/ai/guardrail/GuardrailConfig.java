package com.lrj.langchain4j.ai.guardrail;

import com.lrj.langchain4j.config.LlmConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Guardrail 相关 bean 装配：
 * <ul>
 *   <li>{@link PromptInjectionProperties} 配置绑定</li>
 *   <li>{@link PromptInjectionClassifier} —— 只在 {@code app.guardrail.injection.llm.enabled=true}
 *       时创建。走独立 temp=0 ChatModel（同 Judge / Replanner），避免 prompt 攻击分类被
 *       主 ChatModel 的高温度采样污染</li>
 * </ul>
 *
 * <p>classifier 用 {@link LlmConfig#buildJudgeChatModel} 直接构造 ChatModel，不注册成 Bean ——
 * 项目里只允许一个 {@code ChatModel} 类型 Bean（LC4j @AiService 自动发现会冲突）。
 */
@Configuration
@EnableConfigurationProperties(PromptInjectionProperties.class)
public class GuardrailConfig {

    @Bean
    @ConditionalOnProperty(name = "app.guardrail.injection.llm.enabled", havingValue = "true")
    public PromptInjectionClassifier promptInjectionClassifier(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        ChatModel coldModel = llmConfig.buildJudgeChatModel(props);
        return AiServices.builder(PromptInjectionClassifier.class)
                .chatModel(coldModel)
                .build();
    }
}
