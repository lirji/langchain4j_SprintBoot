package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.ai.cascade.CascadeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Model Cascade / 成本路由入口（{@code app.llm.cascade.enabled=true} 才映射）。走与 {@code /chat}
 * 同一套鉴权链（{@code X-Api-Key} + 多租户 + 限流 + 配额；路径落在 "chat" 限流族）。
 *
 * <p>整个 {@link com.lrj.langchain4j.ai.cascade.CascadeConfig} 条件化：默认关时本控制器不装配、
 * {@code /chat/cascade} 路由不存在，主 {@code Assistant} / {@code /chat} 完全不受影响。
 */
@RestController
@ConditionalOnProperty(name = "app.llm.cascade.enabled", havingValue = "true")
public class CascadeController {

    private final CascadeService cascade;

    public CascadeController(CascadeService cascade) {
        this.cascade = cascade;
    }

    /**
     * 便宜模型先答、低置信才升级强模型。body {@code {"message":"..."}} →
     * {@code {question, answer, served, cheapConfident}}（{@code served} = "cheap"|"strong"，成本可见）。
     */
    @PostMapping("/chat/cascade")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }
        return ResponseEntity.ok(cascade.ask(message));
    }
}
