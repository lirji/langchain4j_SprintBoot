package com.lrj.langchain4j.mcpserver.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * MCP {@code tools/list} 里的单个工具描述符：{@code name} + 自然语言 {@code description}
 * + JSON Schema 形式的 {@code inputSchema}（客户端据此生成调用参数）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
}
