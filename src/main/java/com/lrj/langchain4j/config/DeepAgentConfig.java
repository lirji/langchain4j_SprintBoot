package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.agent.AgentAction;
import com.lrj.langchain4j.ai.agent.AgentBrain;
import com.lrj.langchain4j.ai.agent.AgentProperties;
import com.lrj.langchain4j.ai.agent.DeepAgentService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 深度 Agent 装配。<strong>整个 config 条件化在 {@code app.deep-agent.enabled=true}</strong> ——
 * 关闭（默认）时 {@link AgentBrain} / {@link DeepAgentService} 及示例动作全不存在，零开销。
 *
 * <p>{@link AgentBrain} 复用主 {@code ChatModel}（已挂 metrics + per-tenant token 预算 listener），
 * 程序化 {@code AiServices.builder} 构建、不带 ChatMemory（循环每步显式重注入状态）——
 * 与 {@code Planner}/{@code Critic}/{@code Judge} 同套路，不新建 ChatModel Bean。
 */
@Configuration
@ConditionalOnProperty(name = "app.deep-agent.enabled", havingValue = "true")
public class DeepAgentConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.deep-agent")
    public AgentProperties agentProperties() {
        return new AgentProperties();
    }

    @Bean
    public AgentBrain agentBrain(ChatModel chatModel) {
        return AiServices.builder(AgentBrain.class).chatModel(chatModel).build();
    }

    @Bean
    public DeepAgentService deepAgentService(AgentBrain brain,
                                             List<AgentAction> actions,
                                             AgentProperties props) {
        return new DeepAgentService(brain, actions, props);
    }
}
