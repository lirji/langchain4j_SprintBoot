package com.lrj.langchain4j.mcpserver.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP over JSON-RPC 2.0 的 error 对象。MCP 复用标准 JSON-RPC 2.0 错误码，无 A2A 那样的扩展码
 * （工具执行失败走 {@link CallToolResult#isError()}，不占 JSON-RPC error）。
 *
 * <p>手写协议 record，风格对齐 {@code a2a/protocol/JsonRpcError}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpJsonRpcError(int code, String message, Object data) {

    // —— 标准 JSON-RPC 2.0 错误码 ——
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static McpJsonRpcError of(int code, String message) {
        return new McpJsonRpcError(code, message, null);
    }

    public static McpJsonRpcError methodNotFound(String method) {
        return new McpJsonRpcError(METHOD_NOT_FOUND, "Method not found: " + method, null);
    }

    public static McpJsonRpcError invalidParams(String detail) {
        return new McpJsonRpcError(INVALID_PARAMS, "Invalid params: " + detail, null);
    }
}
