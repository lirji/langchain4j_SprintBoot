package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.multiagent.Planner;
import com.lrj.langchain4j.ai.multiagent.Replanner;
import com.lrj.langchain4j.ai.multiagent.Synthesizer;
import com.lrj.langchain4j.ai.multiagent.Worker;
import com.lrj.langchain4j.security.TenantContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
public class MultiAgentConfig {

    @Bean
    public Planner planner(ChatModel chatModel) {
        return AiServices.builder(Planner.class).chatModel(chatModel).build();
    }

    @Bean
    public Worker worker(ChatModel chatModel) {
        return AiServices.builder(Worker.class).chatModel(chatModel).build();
    }

    /**
     * Synthesizer 同时装 chatModel + streamingChatModel —— 让 {@code synthesizeStream}
     * 能用 TokenStream 返回类型（{@code /chat/multi-agent/stream} 走这条）。
     */
    @Bean
    public Synthesizer synthesizer(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        return AiServices.builder(Synthesizer.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .build();
    }

    /**
     * Replanner 走独立 temp=0 ChatModel（同 Critic / Judge）。replan 是元层决策——
     * 看反馈调结构，温度低更稳定，避免同一 mainIssue 给出两种截然不同的修订方案。
     *
     * <p>不注册成 ChatModel Bean（项目里只允许一个 ChatModel Bean，详见
     * {@link LlmConfig#buildJudgeChatModel} 注释）。
     */
    @Bean
    public Replanner replanner(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        ChatModel coldModel = llmConfig.buildJudgeChatModel(props);
        return AiServices.builder(Replanner.class)
                .chatModel(coldModel)
                .build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.multi-agent.replan")
    public PlanExecuteProperties planExecuteProperties() {
        return new PlanExecuteProperties();
    }

    /**
     * Plan-and-Execute 的 replan 闭环开关与参数。默认关闭——开启后每次 multi-agent
     * 调用至少多 1 次 Critic call，可能多 1 次 Replanner call + 一整轮 worker fan-out
     * 重跑，token 翻倍。
     *
     * <p>{@code weights} 跟 reflexion 同款 3 维加权（默认 0.4/0.4/0.2），但**独立配置**
     * 而非 import {@code ReflexionProperties.Weights}——multi-agent 跟 reflexion 是两个
     * 工程开关，未来想各自调整就独立。
     */
    public static class PlanExecuteProperties {
        private boolean enabled = false;
        private double threshold = 0.75;
        private int maxReplans = 1;
        private Weights weights = new Weights();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public int getMaxReplans() { return maxReplans; }
        public void setMaxReplans(int maxReplans) { this.maxReplans = maxReplans; }
        public Weights getWeights() { return weights; }
        public void setWeights(Weights weights) { this.weights = weights; }

        public static class Weights {
            private double correctness = 0.4;
            private double completeness = 0.4;
            private double clarity = 0.2;

            public double getCorrectness() { return correctness; }
            public void setCorrectness(double correctness) { this.correctness = correctness; }
            public double getCompleteness() { return completeness; }
            public void setCompleteness(double completeness) { this.completeness = completeness; }
            public double getClarity() { return clarity; }
            public void setClarity(double clarity) { this.clarity = clarity; }
        }
    }

    /**
     * Dedicated pool for worker fan-out. Decorator copies the SLF4J MDC so traceIds
     * set on the request thread show up in worker logs.
     */
    @Bean(name = "multiAgentExecutor")
    public Executor multiAgentExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(32);
        exec.setThreadNamePrefix("agent-");
        exec.setTaskDecorator(new MdcCopyingTaskDecorator());
        exec.initialize();
        return exec;
    }

    /**
     * 把请求线程的 MDC（traceId / tenantId 等日志变量）和 {@link TenantContext}（强类型租户身份）
     * 同时透传到子线程。multi-agent worker fan-out 和 eval 子任务都依赖这个 ——
     * 没有它，子线程拿不到 tenant 就会越权或在 RAG 检索时 fail-fast 到 anonymous。
     */
    public static class MdcCopyingTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            TenantContext.Tenant tenant = TenantContext.captureRaw();
            return () -> {
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                TenantContext.Tenant previousTenant = TenantContext.captureRaw();
                if (context != null) MDC.setContextMap(context); else MDC.clear();
                if (tenant != null) TenantContext.set(tenant); else TenantContext.clear();
                try {
                    runnable.run();
                } finally {
                    if (previousMdc != null) MDC.setContextMap(previousMdc); else MDC.clear();
                    if (previousTenant != null) TenantContext.set(previousTenant); else TenantContext.clear();
                }
            };
        }
    }
}
