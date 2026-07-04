package com.lrj.langchain4j.mcpserver.protocol;

/**
 * MCP 工具结果的文本内容块（{@code {"type":"text","text":"..."}}）。本项目工具都返回纯文本，
 * 故只实现 text 类型（image / resource 类型是未来项）。
 */
public record TextContent(String type, String text) {

    public static TextContent text(String text) {
        return new TextContent("text", text);
    }
}
