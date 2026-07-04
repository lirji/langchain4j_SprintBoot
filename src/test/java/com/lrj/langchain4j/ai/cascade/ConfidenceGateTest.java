package com.lrj.langchain4j.ai.cascade;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfidenceGate} 启发式 + 可选自评的确定性单测。
 */
class ConfidenceGateTest {

    private static CascadeProperties props() { return new CascadeProperties(); }

    @Test
    void nullAnswer_lowConfidence() {
        assertFalse(new ConfidenceGate(props()).isConfident("q", null));
    }

    @Test
    void tooShortAnswer_lowConfidence() {
        assertFalse(new ConfidenceGate(props()).isConfident("q", "短"));
    }

    @Test
    void uncertaintyMarker_lowConfidence() {
        ConfidenceGate gate = new ConfidenceGate(props());
        assertFalse(gate.isConfident("q", "抱歉，我无法回答这个问题。"));
        assertFalse(gate.isConfident("q", "I'm not sure about this at all."));
    }

    @Test
    void longConfidentAnswer_highConfidence() {
        assertTrue(new ConfidenceGate(props())
                .isConfident("q", "这是一段足够长、结构完整且自信的答案内容。"));
    }

    @Test
    void selfRating_belowThreshold_escalates() {
        CascadeProperties p = props();
        p.setSelfRating(true);
        p.setConfidenceThreshold(0.6);
        ConfidenceGate gate = new ConfidenceGate(p, fixedRater("0.3"));
        // 启发式过关（够长、无标记），但自评 0.3 < 0.6 → 低置信
        assertFalse(gate.isConfident("q", "这是一段完整清晰、表述肯定的答案内容，用于验证自评通道。"));
    }

    @Test
    void selfRating_aboveThreshold_keepsCheap() {
        CascadeProperties p = props();
        p.setSelfRating(true);
        p.setConfidenceThreshold(0.6);
        ConfidenceGate gate = new ConfidenceGate(p, fixedRater("score: 0.9"));
        assertTrue(gate.isConfident("q", "这是一段完整清晰、表述肯定的答案内容，用于验证自评通道。"));
    }

    @Test
    void selfRating_unparseable_treatedLowConfidence() {
        CascadeProperties p = props();
        p.setSelfRating(true);
        ConfidenceGate gate = new ConfidenceGate(p, fixedRater("无法解析的文本"));
        assertFalse(gate.isConfident("q", "这是一段完整清晰、表述肯定的答案内容，用于验证自评通道。"));
    }

    /** 桩自评模型：chat(String) 固定返回给定分数文本。 */
    private static ChatModel fixedRater(String reply) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
            }
            @Override
            public String chat(String userMessage) {
                return reply; // ConfidenceGate.selfRate 走 chat(String)
            }
            @Override
            public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
            }
        };
    }
}
