package com.lrj.langchain4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
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
