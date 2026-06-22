package com.lrj.langchain4j.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.a2a.A2aService;
import com.lrj.langchain4j.a2a.A2aStreamService;
import com.lrj.langchain4j.a2a.protocol.AgentCard;
import com.lrj.langchain4j.a2a.protocol.JsonRpcError;
import com.lrj.langchain4j.a2a.protocol.JsonRpcResponse;
import com.lrj.langchain4j.a2a.protocol.MessageSendParams;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A Server 入口（{@code app.a2a.enabled=true} 才挂端点）：
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} —— 服务发现（安全链放行，免鉴权）</li>
 *   <li>{@code POST /a2a} —— JSON-RPC 2.0 单端点。{@code message/stream} 返回 SSE，其余返回 JSON-RPC response</li>
 * </ul>
 * 鉴权 / 多租户 / 限流 / token 预算复用现有 filter 链（{@code /a2a} 走 authenticated）。
 */
@RestController
@ConditionalOnProperty(name = "app.a2a.enabled", havingValue = "true")
public class A2aController {

    private final A2aService service;
    private final A2aStreamService streamService;
    private final ObjectMapper json;

    public A2aController(A2aService service, A2aStreamService streamService, ObjectMapper json) {
        this.service = service;
        this.streamService = streamService;
        this.json = json;
    }

    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentCard agentCard() {
        return service.agentCard();
    }

    /**
     * 返回类型声明为 {@code Object}：{@code message/stream} 返回 {@code SseEmitter}（Spring 按实际值类型
     * 走 SSE），其余方法返回 {@code JsonRpcResponse}（Jackson 序列化成 application/json）。
     */
    @PostMapping("/a2a")
    public Object handle(@RequestBody JsonNode body) {
        Object id = idOf(body);

        JsonNode methodNode = body.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            return JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcError.INVALID_REQUEST, "missing or non-string 'method'"));
        }
        String method = methodNode.asText();
        JsonNode params = body.get("params");

        if ("message/stream".equals(method)) {
            try {
                MessageSendParams p = json.convertValue(params, MessageSendParams.class);
                if (p == null || p.message() == null) {
                    return JsonRpcResponse.error(id, JsonRpcError.invalidParams("message is required"));
                }
                return streamService.stream(p.message(), id);
            } catch (IllegalArgumentException e) {
                return JsonRpcResponse.error(id, JsonRpcError.invalidParams(e.getMessage()));
            }
        }

        return service.dispatch(method, params, id);
    }

    /** JSON-RPC id 可为 string / number / null —— 原样回带，类型保持。 */
    private static Object idOf(JsonNode body) {
        JsonNode n = body.get("id");
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        return n.asText();
    }
}
