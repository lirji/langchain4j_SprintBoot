package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 error 对象。code 用标准 JSON-RPC 码 + A2A 扩展码（-32001 TaskNotFound 等）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, Object data) {

    // —— 标准 JSON-RPC 2.0 错误码 ——
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    // —— A2A 扩展错误码 ——
    public static final int TASK_NOT_FOUND = -32001;
    public static final int TASK_NOT_CANCELABLE = -32002;
    public static final int PUSH_NOTIFICATION_NOT_SUPPORTED = -32003;

    public static JsonRpcError of(int code, String message) {
        return new JsonRpcError(code, message, null);
    }

    public static JsonRpcError methodNotFound(String method) {
        return new JsonRpcError(METHOD_NOT_FOUND, "Method not found: " + method, null);
    }

    public static JsonRpcError invalidParams(String detail) {
        return new JsonRpcError(INVALID_PARAMS, "Invalid params: " + detail, null);
    }

    public static JsonRpcError taskNotFound(String taskId) {
        return new JsonRpcError(TASK_NOT_FOUND, "Task not found: " + taskId, null);
    }
}
