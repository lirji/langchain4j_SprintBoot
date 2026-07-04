package com.lrj.langchain4j.mcpserver.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP {@code tools/call} 的 params：要调用的工具 {@code name} + 参数对象 {@code arguments}
 * （原样保留成 {@link JsonNode}，由具体工具自解析，避免协议层写死每个工具的参数形状）。
 */
public record CallToolParams(String name, JsonNode arguments) {
}
