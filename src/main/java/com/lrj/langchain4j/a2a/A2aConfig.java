package com.lrj.langchain4j.a2a;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link A2aProperties}。**无条件**生效（即便 {@code app.a2a.enabled=false}）——
 * 这样 A2A service bean 始终能注入 props 而不至于因缺 bean 启动失败；真正的开关在
 * {@code A2aController}（端点只在 enabled=true 时挂上）。
 */
@Configuration
@EnableConfigurationProperties(A2aProperties.class)
public class A2aConfig {
}
