package com.lrj.langchain4j.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.mcpserver.protocol.CallToolParams;
import com.lrj.langchain4j.mcpserver.protocol.CallToolResult;
import com.lrj.langchain4j.mcpserver.protocol.InitializeResult;
import com.lrj.langchain4j.mcpserver.protocol.McpJsonRpcError;
import com.lrj.langchain4j.mcpserver.protocol.McpJsonRpcResponse;
import com.lrj.langchain4j.mcpserver.protocol.McpToolDescriptor;
import com.lrj.langchain4j.mcpserver.protocol.ToolsListResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 核心：把 JSON-RPC method（{@code initialize} / {@code tools/list} / {@code tools/call}）
 * 路由到自动收集的 {@link McpServerTool} 集合，并做 MCP↔内部模型翻译。反向暴露本 app 能力供外部
 * MCP 客户端（Claude Desktop / Cursor）调入，是 {@code ai/mcp}（client）的镜像。
 *
 * <p>条件化在 {@code app.mcp.server.enabled=true}——关闭时本 bean 不存在，{@code McpServerController}
 * 也不挂端点。工具集靠 Spring 注入 {@code List<McpServerTool>} 自动发现，加能力无需改本类。
 */
@Service
@ConditionalOnProperty(name = "app.mcp.server.enabled", havingValue = "true")
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    private final Map<String, McpServerTool> tools = new LinkedHashMap<>();
    private final McpServerProperties props;
    private final ObjectMapper json;

    public McpServerService(List<McpServerTool> tools, McpServerProperties props, ObjectMapper json) {
        for (McpServerTool t : tools) {
            McpServerTool prev = this.tools.putIfAbsent(t.name(), t);
            if (prev != null) {
                log.warn("MCP tool name collision on '{}': keeping {}, ignoring {}",
                        t.name(), prev.getClass().getSimpleName(), t.getClass().getSimpleName());
            }
        }
        this.props = props;
        this.json = json;
        log.info("MCP server exposing {} tool(s): {}", this.tools.size(), this.tools.keySet());
    }

    /**
     * 分派一个 JSON-RPC 请求。{@code notifications/*}（无 id 的通知，如 {@code notifications/initialized}）
     * 返回 {@code null}——MCP 通知不需要 response，由 controller 回 202/空。
     */
    public McpJsonRpcResponse dispatch(String method, JsonNode params, Object id) {
        if (method != null && method.startsWith("notifications/")) {
            return null;
        }
        try {
            return switch (method == null ? "" : method) {
                case "initialize" -> McpJsonRpcResponse.success(id, initializeResult());
                case "tools/list" -> McpJsonRpcResponse.success(id, toolsList());
                case "tools/call" -> handleToolsCall(params, id);
                default -> McpJsonRpcResponse.error(id, McpJsonRpcError.methodNotFound(method));
            };
        } catch (IllegalArgumentException e) {
            return McpJsonRpcResponse.error(id, McpJsonRpcError.invalidParams(e.getMessage()));
        } catch (Exception e) {
            log.error("MCP method {} failed", method, e);
            return McpJsonRpcResponse.error(id, McpJsonRpcError.of(McpJsonRpcError.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    // —— initialize ——

    private InitializeResult initializeResult() {
        return new InitializeResult(
                props.getProtocolVersion(),
                InitializeResult.Capabilities.toolsOnly(),
                new InitializeResult.ServerInfo(props.getServerName(), props.getServerVersion()));
    }

    // —— tools/list ——

    ToolsListResult toolsList() {
        List<McpToolDescriptor> descriptors = tools.values().stream()
                .map(t -> new McpToolDescriptor(t.name(), t.description(), t.inputSchema()))
                .toList();
        return new ToolsListResult(descriptors);
    }

    // —— tools/call ——

    private McpJsonRpcResponse handleToolsCall(JsonNode params, Object id) {
        CallToolParams p = parse(params, CallToolParams.class);
        if (p == null || p.name() == null || p.name().isBlank()) {
            throw new IllegalArgumentException("tools/call requires a 'name'");
        }
        McpServerTool tool = tools.get(p.name());
        if (tool == null) {
            // 未知工具是协议层问题 → JSON-RPC error（区别于工具执行失败走 isError result）
            throw new IllegalArgumentException("Unknown tool: " + p.name());
        }
        try {
            String text = tool.call(p.arguments());
            return McpJsonRpcResponse.success(id, CallToolResult.ok(text == null ? "" : text));
        } catch (Exception e) {
            // 工具执行失败 → MCP 约定的 isError result（让模型读到错误并重试），不打断 JSON-RPC 信封
            log.warn("MCP tool {} threw", p.name(), e);
            return McpJsonRpcResponse.success(id,
                    CallToolResult.error("工具执行失败：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private <T> T parse(JsonNode params, Class<T> type) {
        if (params == null || params.isNull()) return null;
        return json.convertValue(params, type);
    }
}
