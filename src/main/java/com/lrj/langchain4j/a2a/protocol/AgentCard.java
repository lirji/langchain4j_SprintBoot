package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A2A Agent Card —— 服务发现元数据，发布在 {@code /.well-known/agent-card.json}。
 * 声明 endpoint / 协议能力 / 技能清单 / 认证方式，客户端据此决定怎么调。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCard(String name,
                        String description,
                        String url,
                        String version,
                        String protocolVersion,
                        Capabilities capabilities,
                        List<String> defaultInputModes,
                        List<String> defaultOutputModes,
                        List<Skill> skills,
                        Map<String, SecurityScheme> securitySchemes,
                        List<Map<String, List<String>>> security) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capabilities(boolean streaming, boolean pushNotifications, boolean stateTransitionHistory) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Skill(String id,
                        String name,
                        String description,
                        List<String> tags,
                        List<String> examples,
                        List<String> inputModes,
                        List<String> outputModes) {
    }

    /** apiKey 安全方案：type=apiKey, in=header, name=X-Api-Key（复用项目 ApiKeyAuthFilter）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SecurityScheme(String type, String in, String name, String description) {
    }
}
