package com.lrj.langchain4j.ai.cascade;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Model Cascade 入口（{@code app.llm.cascade.enabled=true} 才映射）。走 {@code /chat} 同套鉴权链
 * （{@code X-Api-Key} + 多租户 + 限流 + 配额）—— 底层两个模型都挂了 {@code ChatModelListener}，
 * token 照常计入当前租户配额。
 *
 * <p>{@code POST /chat/cascade} body {@code {"message":"..."}} → 便宜模型先答、低置信才升级强模型，
 * 返回 {@link CascadeService.Result}（answer / served=cheap|strong / cheapConfident）。
 * {@code served} 字段让「这次省没省钱」一眼可见。
 */
@RestController
@ConditionalOnProperty(name = "app.llm.cascade.enabled", havingValue = "true")
public class CascadeController {

    private final CascadeService cascade;

    public CascadeController(CascadeService cascade) {
        this.cascade = cascade;
    }

    @PostMapping("/chat/cascade")
    public ResponseEntity<?> cascade(@RequestBody Map<String, Object> body) {
        Object m = body.get("message");
        String message = m == null ? "" : m.toString();
        if (message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }
        return ResponseEntity.ok(cascade.ask(message));
    }
}
