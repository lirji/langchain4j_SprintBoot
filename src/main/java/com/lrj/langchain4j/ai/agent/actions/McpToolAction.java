package com.lrj.langchain4j.ai.agent.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.ai.agent.AgentAction;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 深度 Agent 动作：把 MCP server 暴露的外部工具桥进 ReAct 循环。单个 {@code mcp_call} 动作做分派器
 * —— 构造时一次性 {@code listTools()} 缓存工具目录写进 {@link #description()}，模型在 actionInput 里
 * 用 JSON {@code {"tool":"名","args":{...}}} 选具体工具，{@link #run} 转成 {@link ToolExecutionRequest}
 * 交给 {@link McpClient#executeTool}。验证「连 MCP 这种动态发现的外部工具集，也能整体作为一个动作插进循环」。
 *
 * <p><strong>仅 {@code app.deep-agent.enabled} 且 {@code app.mcp.enabled} 同时为 true 时装配</strong>
 * （MCP 关闭时这个动作不出现在清单里）。不复用 {@code McpAssistant}（那是带原生 function-calling 的独立
 * AiService）——这里要的是让深度 Agent 的循环自己控制每步，所以直接持 {@link McpClient} 分派。
 */
@Component
@ConditionalOnProperty(name = {"app.deep-agent.enabled", "app.mcp.enabled"}, havingValue = "true")
public class McpToolAction implements AgentAction {

    private static final Logger log = LoggerFactory.getLogger(McpToolAction.class);

    private final McpClient mcp;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 构造时缓存的工具目录（listTools 可能要往 MCP server 跑一趟，不每步重列）。 */
    private final String catalog;

    public McpToolAction(McpClient mcp) {
        this.mcp = mcp;
        this.catalog = buildCatalog(mcp);
    }

    private static String buildCatalog(McpClient mcp) {
        try {
            StringBuilder sb = new StringBuilder();
            for (ToolSpecification t : mcp.listTools()) {
                sb.append("  · ").append(t.name());
                if (t.description() != null && !t.description().isBlank()) {
                    sb.append("：").append(t.description().trim());
                }
                sb.append('\n');
            }
            return sb.length() == 0 ? "  (MCP server 未暴露工具)" : sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("列举 MCP 工具失败：{}", e.toString());
            return "  (无法列出 MCP 工具：" + e.getMessage() + ")";
        }
    }

    @Override
    public String name() {
        return "mcp_call";
    }

    @Override
    public String description() {
        return "调用 MCP server 暴露的外部工具。actionInput 填 JSON：{\"tool\":\"工具名\",\"args\":{参数对象}}。"
                + "可用工具：\n" + catalog;
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "入参为空：actionInput 请填 JSON {\"tool\":\"工具名\",\"args\":{...}}。";
        }
        String tool;
        String argsJson;
        try {
            JsonNode node = mapper.readTree(input.trim());
            JsonNode toolNode = node.get("tool");
            if (toolNode == null || toolNode.asText().isBlank()) {
                return "JSON 缺少 \"tool\" 字段；格式 {\"tool\":\"工具名\",\"args\":{...}}。";
            }
            tool = toolNode.asText().trim();
            JsonNode argsNode = node.get("args");
            argsJson = (argsNode == null || argsNode.isNull()) ? "{}" : argsNode.toString();
        } catch (Exception e) {
            return "actionInput 不是合法 JSON（" + e.getMessage() + "）；格式 {\"tool\":\"工具名\",\"args\":{...}}。";
        }
        try {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                    .name(tool)
                    .arguments(argsJson)
                    .build();
            ToolExecutionResult result = mcp.executeTool(req);
            String text = result == null ? "" : result.resultText();
            if (result != null && result.isError()) {
                return "MCP 工具 '" + tool + "' 返回错误：" + text;
            }
            return text == null || text.isBlank() ? "(MCP 工具 '" + tool + "' 返回空结果)" : text;
        } catch (Exception e) {
            return "调用 MCP 工具 '" + tool + "' 失败：" + e.getMessage() + "（检查工具名/参数后重试或改走其他动作）";
        }
    }
}
