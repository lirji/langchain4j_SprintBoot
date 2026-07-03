package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.ai.voting.VotingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Voting 入口（{@code app.voting.enabled=true} 才映射）。走 {@code /chat} 同套鉴权链
 * （{@code X-Api-Key} + 多租户 + 限流 + 配额）。
 *
 * <p>{@code POST /chat/vote} body {@code {"question":"...","n":5}}（n 可选，默认 {@code app.voting.n}）
 * → 并行跑 N 次 + 聚合，返回 {@link VotingService.VoteRun}（votes / decision / agreement / confident）。
 */
@RestController
@ConditionalOnProperty(name = "app.voting.enabled", havingValue = "true")
public class VotingController {

    private final VotingService voting;

    public VotingController(VotingService voting) {
        this.voting = voting;
    }

    @PostMapping("/chat/vote")
    public ResponseEntity<?> vote(@RequestBody Map<String, Object> body) {
        Object q = body.get("question");
        String question = q == null ? "" : q.toString();
        if (question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }
        Object nRaw = body.get("n");
        VotingService.VoteRun run = (nRaw instanceof Number num)
                ? voting.vote(question, num.intValue())
                : voting.vote(question);
        return ResponseEntity.ok(run);
    }
}
