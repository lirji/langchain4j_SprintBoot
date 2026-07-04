package com.lrj.langchain4j.mcpserver.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * MCP {@code initialize} 握手响应：协议版本 + 服务端能力 + 服务端信息。
 * 本服务只提供 tools 能力（无 resources / prompts），故 capabilities 里只有 tools 一项。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InitializeResult(String protocolVersion,
                               Capabilities capabilities,
                               ServerInfo serverInfo) {

    /** 声明支持的能力集合。{@code tools} 为空对象即"支持 tools 能力"（MCP 惯例）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capabilities(Map<String, Object> tools) {
        public static Capabilities toolsOnly() {
            return new Capabilities(Map.of());
        }
    }

    public record ServerInfo(String name, String version) {
    }
}
