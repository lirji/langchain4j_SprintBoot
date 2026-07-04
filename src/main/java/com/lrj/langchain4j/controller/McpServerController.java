package com.lrj.langchain4j.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.langchain4j.mcpserver.McpServerService;
import com.lrj.langchain4j.mcpserver.protocol.McpJsonRpcError;
import com.lrj.langchain4j.mcpserver.protocol.McpJsonRpcResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Server 入口（{@code app.mcp.server.enabled=true} 才挂端点）：
 * <ul>
 *   <li>{@code POST /mcp/server} —— MCP over streamable HTTP 的 JSON-RPC 2.0 单端点。
 *       支持 {@code initialize} / {@code tools/list} / {@code tools/call}，以及无 id 的
 *       {@code notifications/*} 通知（回 202 空体）。</li>
 * </ul>
 * 鉴权 / 多租户 / 限流 / token 预算复用现有 filter 链——{@code /mcp/server} <strong>需 X-Api-Key</strong>
 * （不在 {@code SecurityConfig} 白名单里，走 authenticated）。反向能力暴露给外部 MCP 客户端调入。
 */
@RestController
@ConditionalOnProperty(name = "app.mcp.server.enabled", havingValue = "true")
public class McpServerController {

    private final McpServerService service;

    public McpServerController(McpServerService service) {
        this.service = service;
    }

    @PostMapping("/mcp/server")
    public ResponseEntity<McpJsonRpcResponse> handle(@RequestBody JsonNode body) {
        Object id = idOf(body);

        JsonNode methodNode = body.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            return ResponseEntity.ok(McpJsonRpcResponse.error(id,
                    McpJsonRpcError.of(McpJsonRpcError.INVALID_REQUEST, "missing or non-string 'method'")));
        }
        String method = methodNode.asText();
        JsonNode params = body.get("params");

        McpJsonRpcResponse response = service.dispatch(method, params, id);
        if (response == null) {
            // notifications/*：无需回 JSON-RPC response，回 202 空体
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(response);
    }

    /** JSON-RPC id 可为 string / number / null —— 原样回带，类型保持（同 A2aController.idOf）。 */
    private static Object idOf(JsonNode body) {
        JsonNode n = body.get("id");
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        return n.asText();
    }
}
