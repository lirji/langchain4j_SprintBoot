package com.lrj.langchain4j.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.a2a.protocol.A2aMessage;
import com.lrj.langchain4j.a2a.protocol.AgentCard;
import com.lrj.langchain4j.a2a.protocol.JsonRpcError;
import com.lrj.langchain4j.a2a.protocol.JsonRpcResponse;
import com.lrj.langchain4j.a2a.protocol.Part;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2aService 的确定性分派逻辑：JSON-RPC 错误码、skill 解析、Agent Card 字段。
 * 不连模型 —— 只覆盖在调底层 service 之前就能短路的路径（未知 method / 非法 params / 卡片拼装）。
 */
class A2aDispatchTest {

    private final ObjectMapper json = new ObjectMapper();
    // 这些路径不触达下游依赖，故传 null；agentCard/skillOf 只用 props
    private final A2aService svc = new A2aService(
            null, null, null, null, null, null, new A2aProperties(), json);

    private JsonNode parse(String s) {
        try { return json.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void unknownMethod_returnsMethodNotFound() {
        JsonRpcResponse r = svc.dispatch("does/notExist", null, "1");
        assertThat(r.error()).isNotNull();
        assertThat(r.error().code()).isEqualTo(JsonRpcError.METHOD_NOT_FOUND);
        assertThat(r.result()).isNull();
        assertThat(r.id()).isEqualTo("1");
    }

    @Test
    void tasksGet_missingId_returnsInvalidParams() {
        JsonRpcResponse r = svc.dispatch("tasks/get", parse("{}"), 7);
        assertThat(r.error().code()).isEqualTo(JsonRpcError.INVALID_PARAMS);
        assertThat(r.id()).isEqualTo(7);
    }

    @Test
    void messageSend_blankText_returnsInvalidParams() {
        JsonRpcResponse r = svc.dispatch("message/send",
                parse("{\"message\":{\"role\":\"user\",\"parts\":[]}}"), "x");
        assertThat(r.error().code()).isEqualTo(JsonRpcError.INVALID_PARAMS);
    }

    @Test
    void skillOf_readsMetadata_defaultsToChat() {
        A2aMessage withSkill = new A2aMessage("user", List.of(Part.text("hi")),
                null, null, null, Map.of("skill", "multi-agent"));
        A2aMessage noMeta = new A2aMessage("user", List.of(Part.text("hi")),
                null, null, null, null);

        assertThat(svc.skillOf(withSkill)).isEqualTo(A2aService.SKILL_MULTI_AGENT);
        assertThat(svc.skillOf(noMeta)).isEqualTo(A2aService.SKILL_CHAT);
    }

    @Test
    void agentCard_advertisesEndpointStreamingAndAuth() {
        AgentCard card = svc.agentCard();
        assertThat(card.url()).endsWith("/a2a");
        assertThat(card.capabilities().streaming()).isTrue();
        assertThat(card.capabilities().pushNotifications()).isTrue();
        assertThat(card.skills()).extracting(AgentCard.Skill::id)
                .containsExactlyInAnyOrder(A2aService.SKILL_CHAT, A2aService.SKILL_MULTI_AGENT);
        assertThat(card.securitySchemes()).containsKey("apiKey");
        assertThat(card.securitySchemes().get("apiKey").name()).isEqualTo("X-Api-Key");
    }

    @Test
    void agentCard_serializesTaskKindAndMessageKind() throws Exception {
        // A2aMessage/A2aTask 的 kind 字段（@JsonProperty 方法）要能进 JSON
        A2aMessage m = A2aMessage.agentText("hi", "t1", "c1");
        JsonNode node = json.valueToTree(m);
        assertThat(node.get("kind").asText()).isEqualTo("message");
        assertThat(node.get("role").asText()).isEqualTo("agent");
    }
}
