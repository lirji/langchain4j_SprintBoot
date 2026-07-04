package com.lrj.langchain4j.mcpserver.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP over JSON-RPC 2.0 response 信封。{@code result} 与 {@code error} 互斥。
 * 手写协议 record，风格对齐 {@code a2a/protocol/JsonRpcResponse}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpJsonRpcResponse(String jsonrpc, Object id, Object result, McpJsonRpcError error) {

    public static McpJsonRpcResponse success(Object id, Object result) {
        return new McpJsonRpcResponse("2.0", id, result, null);
    }

    public static McpJsonRpcResponse error(Object id, McpJsonRpcError error) {
        return new McpJsonRpcResponse("2.0", id, null, error);
    }
}
