package com.lrj.langchain4j.mcpserver.protocol;

import java.util.List;

/**
 * MCP {@code tools/call} 的 result：内容块列表 + {@code isError} 标志。
 *
 * <p><strong>MCP 约定</strong>：工具执行层面的失败（如坏入参、检索异常）不走 JSON-RPC error，
 * 而是返回 {@code isError=true} 的正常 result，把错误文本放进 content——这样模型能读到错误并
 * 自行改写重试（与本项目 {@code DateTimeTool} 返回可纠错文本的思路一致）。只有协议层面的问题
 * （未知方法 / 未知工具 / 参数缺失）才回 JSON-RPC error。
 */
public record CallToolResult(List<TextContent> content, boolean isError) {

    public static CallToolResult ok(String text) {
        return new CallToolResult(List.of(TextContent.text(text)), false);
    }

    public static CallToolResult error(String text) {
        return new CallToolResult(List.of(TextContent.text(text)), true);
    }
}
