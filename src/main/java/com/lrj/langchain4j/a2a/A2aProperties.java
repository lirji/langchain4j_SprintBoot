package com.lrj.langchain4j.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.a2a.*}：A2A Server 开关 + Agent Card 元数据。默认关（{@code enabled=false}）——
 * 跟 mcp / nl2sql / workflow 一样灰度引入，不影响现有链路。
 *
 * <pre>
 * app.a2a:
 *   enabled: false
 *   agent-name: "LangChain4j Assistant"
 *   agent-description: "..."
 *   base-url: http://localhost:8080   # Agent Card 里对外暴露的 endpoint 前缀
 *   version: "1.0.0"
 * </pre>
 */
@ConfigurationProperties(prefix = "app.a2a")
public class A2aProperties {

    private boolean enabled = false;
    private String agentName = "LangChain4j Assistant";
    private String agentDescription =
            "A LangChain4j + Spring Boot agent exposing chat (sync/streaming) and multi-agent research over the A2A protocol.";
    private String baseUrl = "http://localhost:8080";
    private String version = "1.0.0";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getAgentDescription() { return agentDescription; }
    public void setAgentDescription(String agentDescription) { this.agentDescription = agentDescription; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
}
