package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.agent.AgentAction;
import com.lrj.langchain4j.ai.agent.AgentBrain;
import com.lrj.langchain4j.ai.agent.AgentProperties;
import com.lrj.langchain4j.ai.agent.DeepAgentService;
import com.lrj.langchain4j.ai.agent.ScratchpadSummarizer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * scratchpad 摘要压缩器 —— 仅 {@code app.deep-agent.scratchpad-summary=true} 时装配。
     * 走独立 temp=0 ChatModel（{@link LlmConfig#buildJudgeChatModel}，压缩是确定性任务、避免工作记忆漂移），
     * 不注册成 ChatModel Bean，跟 {@code Critic}/{@code Judge}/{@code SummarizingChatMemory} 摘要器同思路。
     */
    @Bean
    @ConditionalOnProperty(name = "app.deep-agent.scratchpad-summary", havingValue = "true")
    public ScratchpadSummarizer scratchpadSummarizer(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        ChatModel coldModel = llmConfig.buildJudgeChatModel(props);
        return AiServices.builder(ScratchpadSummarizer.class).chatModel(coldModel).build();
    }

    @Bean
    public DeepAgentService deepAgentService(AgentBrain brain,
                                             List<AgentAction> actions,
                                             AgentProperties props,
                                             ObjectProvider<ScratchpadSummarizer> summarizer) {
        // 未开 scratchpad-summary 时 provider 为空 → 传 null，溢出退化为 line-aware 丢弃最旧（零 LLM 开销）
        return new DeepAgentService(brain, actions, props, summarizer.getIfAvailable());
    }
}
