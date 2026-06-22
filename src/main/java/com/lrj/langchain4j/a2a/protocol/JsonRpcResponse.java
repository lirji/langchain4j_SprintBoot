package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 response 信封。{@code result} 和 {@code error} 互斥（成功只填 result，失败只填 error）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(String jsonrpc, Object id, Object result, JsonRpcError error) {

    public static JsonRpcResponse success(Object id, Object result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    public static JsonRpcResponse error(Object id, JsonRpcError error) {
        return new JsonRpcResponse("2.0", id, null, error);
    }
}
