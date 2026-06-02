package com.lrj.langchain4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling 是给 TaskStore.cleanup()（@Scheduled 清理过期异步任务）启用的
// @EnableAsync 是给 WebhookDispatcher.onTaskEvent(@Async("webhookExecutor")) 启用的
//
// 排除 DataSource 自动装配：项目默认无主 SQL 数据源（持久化走 Redis + 向量库）。NL2SQL（app.nl2sql.*）
// 引入了 spring-boot-starter-jdbc，若不排除，Spring Boot 会尝试自动装配一个主 DataSource（缺 url 时启动报错），
// 污染默认启动路径。NL2SQL 的 MySQL DataSource 由 Nl2SqlConfig 在 @ConditionalOnProperty 下手动构建。
// （将来 #1 Flowable 落地时，其引擎 DataSource 同样手动构建 / 或届时改回放开自动装配 + @Qualifier 区分。）
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class})
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
