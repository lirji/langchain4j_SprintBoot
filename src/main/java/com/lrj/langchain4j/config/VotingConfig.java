package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.voting.VoteAggregator;
import com.lrj.langchain4j.ai.voting.Voter;
import com.lrj.langchain4j.ai.voting.VotingService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;

/**
 * Voting 装配（Anthropic Parallelization / Voting 模式）。<strong>整个 config 条件化在
 * {@code app.voting.enabled=true}</strong> —— 关闭（默认）时全不装配，零开销。
 *
 * <p>{@link Voter} 走主 {@code ChatModel}（采样温度制造多样性、token 纳入配额）；{@link VoteAggregator}
 * 走独立 temp=0 判官模型（{@code buildJudgeChatModel}，仅 synthesis 策略用）。fan-out 复用 {@code multiAgentExecutor}。
 */
@Configuration
@ConditionalOnProperty(name = "app.voting.enabled", havingValue = "true")
public class VotingConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.voting")
    public VotingProperties votingProperties() {
        return new VotingProperties();
    }

    @Bean
    public Voter voter(ChatModel chatModel) {
        return AiServices.builder(Voter.class).chatModel(chatModel).build();
    }

    @Bean
    public VoteAggregator voteAggregator(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        return AiServices.builder(VoteAggregator.class)
                .chatModel(llmConfig.buildJudgeChatModel(props))
                .build();
    }

    @Bean
    public VotingService votingService(Voter voter,
                                       VoteAggregator aggregator,
                                       VotingProperties props,
                                       @Qualifier("multiAgentExecutor") Executor executor) {
        return new VotingService(voter, aggregator, props, executor);
    }

    /** {@code app.voting.*} 绑定。 */
    public static class VotingProperties {

        public enum Strategy { MAJORITY, SYNTHESIS }

        private boolean enabled = false;
        /** 并行投票次数。 */
        private int n = 3;
        /** 聚合策略：majority（确定性多数表决）| synthesis（聚合器 LLM 收口）。 */
        private Strategy strategy = Strategy.MAJORITY;
        /** majority 策略的置信阈值：胜出票占比 ≥ 此值才 confident。 */
        private double minAgreement = 0.5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getN() { return n; }
        public void setN(int n) { this.n = n; }
        public Strategy getStrategy() { return strategy; }
        public void setStrategy(Strategy strategy) { this.strategy = strategy; }
        public double getMinAgreement() { return minAgreement; }
        public void setMinAgreement(double minAgreement) { this.minAgreement = minAgreement; }
    }
}
