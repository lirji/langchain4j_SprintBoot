package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.multiagent.Planner;
import com.lrj.langchain4j.ai.multiagent.Synthesizer;
import com.lrj.langchain4j.ai.multiagent.Worker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.MDC;
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

    static class MdcCopyingTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) MDC.setContextMap(context); else MDC.clear();
                try {
                    runnable.run();
                } finally {
                    if (previous != null) MDC.setContextMap(previous); else MDC.clear();
                }
            };
        }
    }
}
