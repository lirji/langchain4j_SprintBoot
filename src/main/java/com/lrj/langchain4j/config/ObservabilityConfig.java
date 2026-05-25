package com.lrj.langchain4j.config;

import com.lrj.langchain4j.observability.LoggingChatModelListener;
import com.lrj.langchain4j.observability.MetricsChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers ChatModelListener beans. The LangChain4j Spring Boot starter scans
 * for these and wires them into every auto-configured ChatModel / StreamingChatModel,
 * so each LLM call is observed without further changes.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public ChatModelListener loggingChatModelListener() {
        return new LoggingChatModelListener();
    }

    @Bean
    public ChatModelListener metricsChatModelListener(MeterRegistry registry) {
        return new MetricsChatModelListener(registry);
    }
}
