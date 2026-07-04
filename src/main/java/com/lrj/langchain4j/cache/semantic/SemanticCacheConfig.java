package com.lrj.langchain4j.cache.semantic;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 语义响应缓存装配。<strong>整个 config 条件化在 {@code app.cache.semantic.enabled=true}</strong>，
 * 关闭（默认）时 {@link SemanticCacheProperties} 不绑定、{@link SemanticCache}（同条件的 {@code @Component}）
 * 也不装配——对话链零变化。
 *
 * <p>缓存本体 {@link SemanticCache} 是 {@code @Component}（自身也带同一 {@code @ConditionalOnProperty}），
 * 由组件扫描发现；此处只负责把 {@code app.cache.semantic.*} 绑定成 Bean（同 {@code MemoryProfileConfig} 范式）。
 */
@Configuration
@ConditionalOnProperty(name = "app.cache.semantic.enabled", havingValue = "true")
@EnableConfigurationProperties(SemanticCacheProperties.class)
public class SemanticCacheConfig {
}
