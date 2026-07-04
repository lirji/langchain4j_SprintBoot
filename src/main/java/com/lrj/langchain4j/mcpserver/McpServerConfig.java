package com.lrj.langchain4j.mcpserver;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link McpServerProperties}。**无条件**生效（即便 {@code app.mcp.server.enabled=false}）——
 * 与 {@code A2aConfig} 同思路：让 props 始终可注入，真正的开关在各 {@code @Component}
 * （{@link McpServerService} / tools / controller 都条件化在 {@code app.mcp.server.enabled=true}）。
 */
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
public class McpServerConfig {
}
