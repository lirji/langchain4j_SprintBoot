package com.lrj.langchain4j.ai.agent.actions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * code_exec 动作的装配。<strong>整个 config 条件化在 {@code app.deep-agent.enabled} 且
 * {@code app.deep-agent.code-exec.enabled} 同时为 true</strong>——任一为假时 {@link CodeExecProperties}
 * Bean 不存在，配合 {@link CodeExecAction} 上同样的双开关 {@code @ConditionalOnProperty}，
 * 关闭（默认）时零装配、零开销。
 *
 * <p>只注册 {@link CodeExecProperties}（绑定 {@code app.deep-agent.code-exec.*}），动作本体走
 * {@code @Component} 组件扫描自动发现——与 {@code DeepAgentConfig} 里 {@code AgentProperties} 的
 * {@code @Bean + @ConfigurationProperties} 套路一致。
 */
@Configuration
@ConditionalOnProperty(name = {"app.deep-agent.enabled", "app.deep-agent.code-exec.enabled"}, havingValue = "true")
public class CodeExecConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.deep-agent.code-exec")
    public CodeExecProperties codeExecProperties() {
        return new CodeExecProperties();
    }
}
