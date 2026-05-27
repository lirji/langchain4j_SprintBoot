package com.lrj.langchain4j.async.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Webhook 专属线程池。**不复用 {@code multiAgentExecutor}** —— webhook 失败重试可能要等 1+3+9=13s，
 * 占着 worker 线程会噎住主任务 fan-out。
 *
 * <p>{@code TaskDecorator} 透传 MDC（traceId/tenantId/userId），保证 webhook delivery 失败的日志
 * 能跟原 task 串起来。{@code TenantContext} 不必透传 —— webhook 接收方是外部 URL，不该带本地租户身份。
 */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {

    @Bean(name = "webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("webhook-");
        exec.setTaskDecorator(new MdcOnlyTaskDecorator());
        exec.initialize();
        return exec;
    }

    /**
     * 跟 {@code MultiAgentConfig.MdcCopyingTaskDecorator} 类似，但只透传 MDC 不透传 TenantContext ——
     * webhook 发给外部，不该带租户身份。MDC（traceId）保留方便日志追溯。
     */
    static class MdcOnlyTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> prev = org.slf4j.MDC.getCopyOfContextMap();
                if (ctx != null) org.slf4j.MDC.setContextMap(ctx); else org.slf4j.MDC.clear();
                try {
                    runnable.run();
                } finally {
                    if (prev != null) org.slf4j.MDC.setContextMap(prev); else org.slf4j.MDC.clear();
                }
            };
        }
    }
}
