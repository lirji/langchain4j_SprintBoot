package com.lrj.langchain4j.config;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 把 Spring {@link ApplicationContext} 暴露给**非 Spring 管理**的代码 —— 具体是
 * {@link SpringClassInstanceFactory}（通过 JDK {@code ServiceLoader} 加载，拿不到 Spring 注入）。
 *
 * <p>静态持有 context 是一个温和的反模式，但这里是必要的：LangChain4j 的
 * {@code ClassInstanceLoader} 走 SPI 实例化 {@code @InputGuardrails}/{@code @OutputGuardrails}
 * 引用的 guardrail 类，而 SPI 工厂无法被 Spring 装配，只能静态取 context 再解析 bean。
 */
@Component
public class SpringContextHolder implements ApplicationContextAware {

    private static volatile ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static ApplicationContext getApplicationContext() {
        return context;
    }
}
