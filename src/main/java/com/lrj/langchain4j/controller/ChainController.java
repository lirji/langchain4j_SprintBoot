package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.ai.chaining.ChainStep;
import com.lrj.langchain4j.ai.chaining.PromptChainService;
import com.lrj.langchain4j.config.ChainingConfig.ChainProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Prompt Chaining 入口（{@code app.chaining.enabled=true} 才映射）。走 {@code /chat} 同套鉴权链
 * （{@code X-Api-Key} + 多租户 + 限流 + 配额）。
 *
 * <p>{@code POST /chat/chain} body {@code {"input":"..."}} → 跑 yml 里预定义的默认链
 * （{@code app.chaining.steps}），返回逐步 trace + 是否全程通过。
 */
@RestController
@ConditionalOnProperty(name = "app.chaining.enabled", havingValue = "true")
public class ChainController {

    private final PromptChainService chain;
    private final ChainProperties props;

    public ChainController(PromptChainService chain, ChainProperties props) {
        this.chain = chain;
        this.props = props;
    }

    @PostMapping("/chat/chain")
    public ResponseEntity<?> chain(@RequestBody Map<String, String> body) {
        String input = body.getOrDefault("input", "");
        if (input.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "input is required"));
        }
        List<ChainStep> steps = props.getSteps();
        if (steps == null || steps.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no chain steps configured (app.chaining.steps)"));
        }
        return ResponseEntity.ok(chain.run(input, steps));
    }
}
