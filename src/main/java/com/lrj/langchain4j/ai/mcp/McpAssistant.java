package com.lrj.langchain4j.ai.mcp;

/**
 * A stripped-down Assistant whose tools are supplied entirely by an MCP server.
 * No ChatMemory, no RAG retriever — keeps the MCP demo focused on tool-use.
 */
public interface McpAssistant {

    String chat(String userMessage);
}
