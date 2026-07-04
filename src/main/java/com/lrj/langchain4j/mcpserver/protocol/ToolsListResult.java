package com.lrj.langchain4j.mcpserver.protocol;

import java.util.List;

/** MCP {@code tools/list} 的 result：可用工具描述符列表。 */
public record ToolsListResult(List<McpToolDescriptor> tools) {
}
