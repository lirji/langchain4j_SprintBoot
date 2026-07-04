package com.lrj.langchain4j.mcpserver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.langchain4j.ai.tools.DateTimeTool;
import com.lrj.langchain4j.mcpserver.McpServerTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具：当前时间。直接复用现有 {@link DateTimeTool}（同一份实现既给内部 {@code @AiService}
 * 用、也桥出去给外部 MCP 客户端用），验证"暴露能力 = 把已有 tool 包一层协议适配"。
 *
 * <p>条件化在 {@code app.mcp.server.enabled=true}。
 */
@Component
@ConditionalOnProperty(name = "app.mcp.server.enabled", havingValue = "true")
public class DateTimeMcpTool implements McpServerTool {

    private final DateTimeTool delegate;

    public DateTimeMcpTool(DateTimeTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return "current_datetime";
    }

    @Override
    public String description() {
        return "Return the current wall-clock date and time in a given IANA time zone "
                + "(e.g. Asia/Shanghai, UTC, Europe/Paris). Use for \"what time is it / today's date\". "
                + "Argument `zoneId` must be a canonical IANA zone id, not aliases like GMT+8 / CST.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "zoneId", Map.of(
                                "type", "string",
                                "description", "IANA time zone id, e.g. Asia/Shanghai. Defaults to Asia/Shanghai.")),
                "required", List.of());
    }

    @Override
    public String call(JsonNode arguments) {
        String zoneId = stringArg(arguments, "zoneId");
        // DateTimeTool 自身对空 / 坏 zoneId 返回可纠错文本，不抛异常
        return delegate.currentDateTime(zoneId);
    }
}
