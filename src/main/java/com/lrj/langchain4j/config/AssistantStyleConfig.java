package com.lrj.langchain4j.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时按当前 {@code app.llm.provider} 把 {@link AssistantProperties} 解析成
 * {@link ResolvedAssistantStyle}，所有需要 prompt @V 参数的调用方注入这个 Bean 即可。
 *
 * <p>把"配置定义"和"运行时生效"两件事解耦：AssistantProperties 描述 "可能怎么配"，
 * ResolvedAssistantStyle 是 "本进程实际用哪份"。Provider 切换需要重启。
 */
@Configuration
public class AssistantStyleConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantStyleConfig.class);

    @Bean
    public ResolvedAssistantStyle resolvedAssistantStyle(AssistantProperties props,
                                                         LlmConfig.LlmProperties llmProps) {
        String provider = llmProps.getProvider();
        ResolvedAssistantStyle style = props.resolve(provider);
        boolean overridden = props.getOverrides() != null && props.getOverrides().containsKey(provider);
        log.info("ResolvedAssistantStyle for provider={} (override={})", provider, overridden);
        return style;
    }
}
