package com.lrj.langchain4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling 是给 TaskStore.cleanup()（@Scheduled 清理过期异步任务）启用的
// @EnableAsync 是给 WebhookDispatcher.onTaskEvent(@Async("webhookExecutor")) 启用的
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class LangChain4jApplication {

    public static void main(String[] args) {
        // classpath 里同时有 spring-restclient 与 jdk 两个 LangChain4j HTTP client 实现，
        // 不显式指定 ServiceLoader 会抛 "multiple HTTP clients" 冲突；统一用 JDK HttpClient。
        System.setProperty(
                "langchain4j.http.clientBuilderFactory",
                "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory");
        SpringApplication.run(LangChain4jApplication.class, args);
    }
}
