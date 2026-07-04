package com.lrj.langchain4j.mcpserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.mcp.server.*}：把本 app 能力反向暴露成 MCP server 的开关 + 元数据。默认关
 * （{@code enabled=false}）——跟 mcp（client）/ a2a / nl2sql 一样灰度引入，不影响现有链路。
 *
 * <p>注意与 {@code app.mcp.*}（MCP <em>client</em>，把外部 server 的工具桥进来）区分：本前缀是
 * {@code app.mcp.server.*}，方向相反（把自身工具桥出去给 Claude Desktop / Cursor 调）。
 *
 * <pre>
 * app.mcp.server:
 *   enabled: false
 *   server-name: "langchain4j-app"
 *   server-version: "1.0.0"
 *   protocol-version: "2024-11-05"   # MCP 协议版本，随 initialize 回给客户端
 * </pre>
 */
@ConfigurationProperties(prefix = "app.mcp.server")
public class McpServerProperties {

    private boolean enabled = false;
    private String serverName = "langchain4j-app";
    private String serverVersion = "1.0.0";
    /** MCP 协议版本号，initialize 握手回给客户端。默认对齐 MCP 2024-11-05 spec。 */
    private String protocolVersion = "2024-11-05";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getServerVersion() { return serverVersion; }
    public void setServerVersion(String serverVersion) { this.serverVersion = serverVersion; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
}
