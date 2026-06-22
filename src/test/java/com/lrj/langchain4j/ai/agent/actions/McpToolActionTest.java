package com.lrj.langchain4j.ai.agent.actions;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolAction} 的确定性单测（不连真 MCP server）：用假 {@link McpClient} 喂工具目录 + 执行结果，
 * 验证目录进描述 / JSON 入参解析成 {@link ToolExecutionRequest} / 缺字段·坏 JSON·执行错误·异常的可纠错降级。
 */
class McpToolActionTest {

    /** 假 MCP client：只实现 listTools/executeTool，记录最后一次执行请求；其余方法不应被触达。 */
    static class FakeMcpClient implements McpClient {
        ToolExecutionRequest lastReq;
        boolean returnError = false;
        boolean throwOnExecute = false;

        @Override public List<ToolSpecification> listTools() {
            return List.of(
                    ToolSpecification.builder().name("get_weather").description("查天气").build(),
                    ToolSpecification.builder().name("list_dir").description("列目录").build());
        }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest request) {
            this.lastReq = request;
            if (throwOnExecute) throw new RuntimeException("transport down");
            return ToolExecutionResult.builder()
                    .isError(returnError)
                    .resultText(returnError ? "boom" : "result-for:" + request.name())
                    .build();
        }

        // ---- 其余 McpClient 方法：本测试不该触达 ----
        @Override public String key() { throw new UnsupportedOperationException(); }
        @Override public List<ToolSpecification> listTools(dev.langchain4j.invocation.InvocationContext c) { throw new UnsupportedOperationException(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest r, dev.langchain4j.invocation.InvocationContext c) { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpResource> listResources() { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpResource> listResources(dev.langchain4j.invocation.InvocationContext c) { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpResourceTemplate> listResourceTemplates() { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpResourceTemplate> listResourceTemplates(dev.langchain4j.invocation.InvocationContext c) { throw new UnsupportedOperationException(); }
        @Override public dev.langchain4j.mcp.client.McpReadResourceResult readResource(String uri) { throw new UnsupportedOperationException(); }
        @Override public dev.langchain4j.mcp.client.McpReadResourceResult readResource(String uri, dev.langchain4j.invocation.InvocationContext c) { throw new UnsupportedOperationException(); }
        @Override public void subscribeToResource(String uri) { throw new UnsupportedOperationException(); }
        @Override public void unsubscribeFromResource(String uri) { throw new UnsupportedOperationException(); }
        @Override public List<dev.langchain4j.mcp.client.McpPrompt> listPrompts() { throw new UnsupportedOperationException(); }
        @Override public dev.langchain4j.mcp.client.McpGetPromptResult getPrompt(String name, java.util.Map<String, Object> args) { throw new UnsupportedOperationException(); }
        @Override public void checkHealth() { throw new UnsupportedOperationException(); }
        @Override public void setRoots(List<dev.langchain4j.mcp.client.McpRoot> roots) { throw new UnsupportedOperationException(); }
        @Override public void close() {}
    }

    @Test
    void description_listsAvailableTools() {
        McpToolAction action = new McpToolAction(new FakeMcpClient());
        assertEquals("mcp_call", action.name());
        String desc = action.description();
        assertTrue(desc.contains("get_weather"), "工具目录应进描述供模型选择");
        assertTrue(desc.contains("查天气"));
    }

    @Test
    void run_parsesJsonAndDispatches() {
        FakeMcpClient mcp = new FakeMcpClient();
        String obs = new McpToolAction(mcp).run("{\"tool\":\"get_weather\",\"args\":{\"city\":\"上海\"}}");
        assertEquals("result-for:get_weather", obs);
        assertEquals("get_weather", mcp.lastReq.name());
        assertTrue(mcp.lastReq.arguments().contains("上海"), "args 子对象应作为 JSON 透传");
    }

    @Test
    void run_missingArgs_defaultsToEmptyObject() {
        FakeMcpClient mcp = new FakeMcpClient();
        new McpToolAction(mcp).run("{\"tool\":\"list_dir\"}");
        assertEquals("{}", mcp.lastReq.arguments(), "缺 args 时应传空对象");
    }

    @Test
    void run_missingToolField_isCorrectable() {
        FakeMcpClient mcp = new FakeMcpClient();
        String obs = new McpToolAction(mcp).run("{\"args\":{}}");
        assertTrue(obs.contains("tool"), "缺 tool 字段应返回可纠错提示");
    }

    @Test
    void run_invalidJson_isCorrectable() {
        String obs = new McpToolAction(new FakeMcpClient()).run("get_weather 上海");
        assertTrue(obs.contains("JSON"), "非 JSON 入参应返回可纠错提示而非抛异常");
    }

    @Test
    void run_toolReturnsError_surfacedAsText() {
        FakeMcpClient mcp = new FakeMcpClient();
        mcp.returnError = true;
        String obs = new McpToolAction(mcp).run("{\"tool\":\"get_weather\",\"args\":{}}");
        assertTrue(obs.contains("返回错误"), "工具 isError 应转成可读文本");
        assertTrue(obs.contains("boom"));
    }

    @Test
    void run_executeThrows_degradesToText() {
        FakeMcpClient mcp = new FakeMcpClient();
        mcp.throwOnExecute = true;
        String obs = new McpToolAction(mcp).run("{\"tool\":\"get_weather\",\"args\":{}}");
        assertTrue(obs.contains("失败"), "执行异常应降级成可纠错文本");
        assertTrue(obs.contains("transport down"));
    }
}
