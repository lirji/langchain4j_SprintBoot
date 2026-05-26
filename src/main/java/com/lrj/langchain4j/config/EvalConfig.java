package com.lrj.langchain4j.config;

import com.lrj.langchain4j.eval.Judge;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 评分配置
 */
@Configuration
public class EvalConfig {

    /**
     * Judge 走独立的 ChatModel 实例（temperature=0），让"同样的 answer 多次评分"
     * 尽量给出相同的 score —— eval harness 的对照实验才能有效。
     *
     * <p>这里直接调 {@link LlmConfig#buildJudgeChatModel} 而不是注入一个 Judge ChatModel Bean，
     * 是为了避免 LangChain4j 的 {@code @AiService} 自动发现因为多个 {@code ChatModel} 类型 Bean
     * 报 conflict（详见 {@code LlmConfig#buildJudgeChatModel} 的注释）。
     */
    @Bean
    public Judge judge(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        ChatModel judgeModel = llmConfig.buildJudgeChatModel(props);
        return AiServices.builder(Judge.class).chatModel(judgeModel).build();
    }

    /**
     * Eval 并行执行的独立线程池。
     *
     * <p>不复用 {@code multiAgentExecutor} —— 如果 eval case 的 type 是 {@code multi-agent}，
     * 复用同一池会出现"eval thread 占着池等 worker，但 worker 也要从同一池拿 thread"的死锁。
     *
     * <p>大小由 {@code app.eval.concurrency} 控制（默认 4）。把 1 设给它就退回顺序执行 ——
     * 对照 baseline 或排查问题时有用。LLM provider 限流敏感的话也调小。
     */
    @Bean(name = "evalExecutor")
    public Executor evalExecutor(@Value("${app.eval.concurrency:4}") int concurrency) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(concurrency);
        exec.setMaxPoolSize(concurrency);
        exec.setQueueCapacity(128);
        exec.setThreadNamePrefix("eval-");
        // 复用 MultiAgentConfig 里写过的 MDC 透传 decorator —— 同 package，直接引用
        exec.setTaskDecorator(new MultiAgentConfig.MdcCopyingTaskDecorator());
        exec.initialize();
        return exec;
    }
}
