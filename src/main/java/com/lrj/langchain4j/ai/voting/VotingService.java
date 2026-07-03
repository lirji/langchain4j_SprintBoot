package com.lrj.langchain4j.ai.voting;

import com.lrj.langchain4j.config.VotingConfig.VotingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Voting 编排（Anthropic Parallelization / Voting 模式）：同一问题<strong>并行</strong>跑 N 个 {@link Voter}，
 * 再按策略聚合——{@code majority}（确定性多数表决，离散/分类题）或 {@code synthesis}（{@link VoteAggregator}
 * LLM 收口，自由文本题）。
 *
 * <p>与 {@code multiagent}（Sectioning：把<strong>不同</strong>子任务并行）互补：这里是把<strong>同一</strong>
 * 任务并行多跑取共识，降低单次随机性、提升可信度。fan-out 复用 {@code multiAgentExecutor}。
 */
public class VotingService {

    private static final Logger log = LoggerFactory.getLogger(VotingService.class);

    private final Voter voter;
    private final VoteAggregator aggregator; // 仅 synthesis 策略用，可为 null
    private final VotingProperties props;
    private final Executor executor;

    public VotingService(Voter voter, VoteAggregator aggregator, VotingProperties props, Executor executor) {
        this.voter = voter;
        this.aggregator = aggregator;
        this.props = props;
        this.executor = executor;
    }

    /**
     * 一次投票的产物。
     *
     * @param agreement majority 策略下 = 胜出票数 / 总票数（synthesis 下为 {@code NaN}）
     * @param confident majority 下 = {@code agreement >= minAgreement}（synthesis 下恒 true）
     */
    public record VoteRun(String question, List<String> votes, String strategy,
                          String decision, double agreement, boolean confident) {}

    public VoteRun vote(String question) {
        return vote(question, props.getN());
    }

    public VoteRun vote(String question, int n) {
        int rounds = Math.max(1, n);
        List<String> votes = fanOut(question, rounds);

        if (props.getStrategy() == VotingProperties.Strategy.SYNTHESIS) {
            String merged = aggregator != null
                    ? safe(aggregator.merge(question, joinForAggregator(votes)))
                    : votes.get(0); // 未装配聚合器则退化取首票
            log.info("voting synthesis over {} votes", rounds);
            return new VoteRun(question, votes, "synthesis", merged, Double.NaN, true);
        }
        return majority(question, votes);
    }

    /** 并行跑 N 个投票者（复用注入的 executor）。 */
    private List<String> fanOut(String question, int n) {
        List<CompletableFuture<String>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> safe(voter.answer(question)), executor));
        }
        List<String> out = new ArrayList<>(n);
        for (CompletableFuture<String> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    /** 多数表决：归一化（trim + lower）后计票，取最高票；保序取第一个出现的原文作决策。 */
    private VoteRun majority(String question, List<String> votes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> firstRaw = new LinkedHashMap<>();
        for (String v : votes) {
            String key = normalize(v);
            counts.merge(key, 1, Integer::sum);
            firstRaw.putIfAbsent(key, v);
        }
        String bestKey = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        int best = counts.getOrDefault(bestKey, 0);
        double agreement = votes.isEmpty() ? 0.0 : (double) best / votes.size();
        boolean confident = agreement >= props.getMinAgreement();
        log.info("voting majority: {}/{} agree (min={}), confident={}",
                best, votes.size(), props.getMinAgreement(), confident);
        return new VoteRun(question, votes, "majority", firstRaw.getOrDefault(bestKey, ""), agreement, confident);
    }

    private static String normalize(String s) {
        return safe(s).strip().toLowerCase(Locale.ROOT);
    }

    private static String joinForAggregator(List<String> votes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < votes.size(); i++) {
            sb.append("[回答 ").append(i + 1).append("] ").append(votes.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
