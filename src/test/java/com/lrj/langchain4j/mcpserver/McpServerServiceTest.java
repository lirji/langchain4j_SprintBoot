package com.lrj.langchain4j.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.mcpserver.protocol.CallToolResult;
import com.lrj.langchain4j.mcpserver.protocol.InitializeResult;
import com.lrj.langchain4j.mcpserver.protocol.McpJsonRpcError;
import com.lrj.langchain4j.mcpserver.protocol.McpJsonRpcResponse;
import com.lrj.langchain4j.mcpserver.protocol.ToolsListResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Server 分派的确定性逻辑：{@code initialize} / {@code tools/list} 的描述符拼装、
 * {@code tools/call} 派发到 stub 工具、结果与错误如何映射进 JSON-RPC / MCP result。
 * 不连模型 / DB / 网络——用 stub {@link McpServerTool}。
 */
class McpServerServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode parse(String s) {
        try { return json.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 记录被调用的 arguments，可配置成正常返回 / 抛异常。 */
    private static final class StubTool implements McpServerTool {
        private final String name;
        private final boolean throwing;
        final AtomicReference<JsonNode> lastArgs = new AtomicReference<>();

        StubTool(String name, boolean throwing) {
            this.name = name;
            this.throwing = throwing;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "stub " + name; }
        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object",
                    "properties", Map.of("q", Map.of("type", "string")),
                    "required", List.of("q"));
        }
        @Override public String call(JsonNode arguments) {
            lastArgs.set(arguments);
            if (throwing) throw new IllegalStateException("boom");
            return "echo:" + stringArg(arguments, "q");
        }
    }

    private McpServerService svc(McpServerTool... tools) {
        return new McpServerService(List.of(tools), new McpServerProperties(), json);
    }

    // —— initialize ——

    @Test
    void initialize_returnsProtocolAndServerInfo() {
        McpJsonRpcResponse r = svc(new StubTool("t1", false)).dispatch("initialize", null, 1);
        assertThat(r.error()).isNull();
        InitializeResult res = (InitializeResult) r.result();
        assertThat(res.protocolVersion()).isEqualTo("2024-11-05");
        assertThat(res.serverInfo().name()).isEqualTo("langchain4j-app");
        assertThat(res.capabilities().tools()).isNotNull();
        assertThat(r.id()).isEqualTo(1);
    }

    // —— tools/list ——

    @Test
    void toolsList_returnsExpectedDescriptors() {
        McpJsonRpcResponse r = svc(new StubTool("alpha", false), new StubTool("beta", false))
                .dispatch("tools/list", null, "abc");
        ToolsListResult res = (ToolsListResult) r.result();
        assertThat(res.tools()).hasSize(2);
        assertThat(res.tools().stream().map(t -> t.name())).containsExactly("alpha", "beta");
        assertThat(res.tools().get(0).description()).isEqualTo("stub alpha");
        assertThat(res.tools().get(0).inputSchema()).containsEntry("type", "object");
        assertThat(r.id()).isEqualTo("abc");
    }

    @Test
    void duplicateToolName_firstWins() {
        StubTool first = new StubTool("dup", false);
        StubTool second = new StubTool("dup", false);
        ToolsListResult res = (ToolsListResult) svc(first, second)
                .dispatch("tools/list", null, 1).result();
        assertThat(res.tools()).hasSize(1);
    }

    // —— tools/call: dispatch + result mapping ——

    @Test
    void toolsCall_dispatchesToStub_andWrapsResult() {
        StubTool tool = new StubTool("echoer", false);
        McpJsonRpcResponse r = svc(tool).dispatch("tools/call",
                parse("{\"name\":\"echoer\",\"arguments\":{\"q\":\"hi\"}}"), 9);
        assertThat(r.error()).isNull();
        CallToolResult res = (CallToolResult) r.result();
        assertThat(res.isError()).isFalse();
        assertThat(res.content()).hasSize(1);
        assertThat(res.content().get(0).type()).isEqualTo("text");
        assertThat(res.content().get(0).text()).isEqualTo("echo:hi");
        // 确认 arguments 原样透传给了工具
        assertThat(tool.lastArgs.get().get("q").asText()).isEqualTo("hi");
    }

    @Test
    void toolsCall_toolThrows_mapsToIsErrorResult_notJsonRpcError() {
        McpJsonRpcResponse r = svc(new StubTool("boomer", true)).dispatch("tools/call",
                parse("{\"name\":\"boomer\",\"arguments\":{\"q\":\"x\"}}"), 3);
        // 工具执行失败走 MCP isError result，而非 JSON-RPC error
        assertThat(r.error()).isNull();
        CallToolResult res = (CallToolResult) r.result();
        assertThat(res.isError()).isTrue();
        assertThat(res.content().get(0).text()).contains("boom");
    }

    @Test
    void toolsCall_unknownTool_returnsInvalidParams() {
        McpJsonRpcResponse r = svc(new StubTool("known", false)).dispatch("tools/call",
                parse("{\"name\":\"ghost\",\"arguments\":{}}"), 4);
        assertThat(r.result()).isNull();
        assertThat(r.error().code()).isEqualTo(McpJsonRpcError.INVALID_PARAMS);
        assertThat(r.error().message()).contains("ghost");
    }

    @Test
    void toolsCall_missingName_returnsInvalidParams() {
        McpJsonRpcResponse r = svc(new StubTool("known", false)).dispatch("tools/call",
                parse("{\"arguments\":{\"q\":\"x\"}}"), 5);
        assertThat(r.error().code()).isEqualTo(McpJsonRpcError.INVALID_PARAMS);
    }

    // —— unknown method / notifications ——

    @Test
    void unknownMethod_returnsMethodNotFound() {
        McpJsonRpcResponse r = svc(new StubTool("t", false)).dispatch("does/notExist", null, "7");
        assertThat(r.error().code()).isEqualTo(McpJsonRpcError.METHOD_NOT_FOUND);
        assertThat(r.id()).isEqualTo("7");
    }

    @Test
    void notification_returnsNullResponse() {
        McpJsonRpcResponse r = svc(new StubTool("t", false))
                .dispatch("notifications/initialized", null, null);
        assertThat(r).isNull();
    }
}
