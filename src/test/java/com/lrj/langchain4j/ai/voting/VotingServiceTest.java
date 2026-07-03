package com.lrj.langchain4j.ai.voting;

import com.lrj.langchain4j.config.VotingConfig.VotingProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Voting 的确定性单测（不连模型）：脚本化 {@link Voter} 按序吐预设答案，用同步 executor 让 fan-out
 * 可预测，验证多数表决 / 置信阈值 / synthesis 聚合。
 */
class VotingServiceTest {

    /** 同步 executor：直接在当前线程跑，fan-out 结果顺序确定。 */
    private static final Executor INLINE = Runnable::run;

    /** 按队列顺序吐预设答案的投票者。 */
    static class ScriptedVoter implements Voter {
        final Deque<String> answers = new ArrayDeque<>();
        ScriptedVoter(String... a) { for (String s : a) answers.add(s); }
        @Override public String answer(String question) {
            String s = answers.poll();
            return s != null ? s : "";
        }
    }

    static VotingProperties props(VotingProperties.Strategy strategy, int n, double minAgreement) {
        VotingProperties p = new VotingProperties();
        p.setStrategy(strategy);
        p.setN(n);
        p.setMinAgreement(minAgreement);
        return p;
    }

    @Test
    void majority_picksMostFrequent_andComputesAgreement() {
        var voter = new ScriptedVoter("是", "是", "否");
        var svc = new VotingService(voter, null, props(VotingProperties.Strategy.MAJORITY, 3, 0.5), INLINE);
        var run = svc.vote("该批准吗？");
        assertEquals("majority", run.strategy());
        assertEquals("是", run.decision());
        assertEquals(2.0 / 3, run.agreement(), 1e-9);
        assertTrue(run.confident(), "2/3 ≥ 0.5 → confident");
        assertEquals(3, run.votes().size());
    }

    @Test
    void majority_normalizesCaseAndWhitespace() {
        // "Yes" / " yes " / "no" 归一化后 yes=2 → 决策取第一个原文 "Yes"
        var voter = new ScriptedVoter("Yes", " yes ", "no");
        var svc = new VotingService(voter, null, props(VotingProperties.Strategy.MAJORITY, 3, 0.5), INLINE);
        var run = svc.vote("q");
        assertEquals("Yes", run.decision());
        assertEquals(2.0 / 3, run.agreement(), 1e-9);
    }

    @Test
    void majority_belowThreshold_notConfident() {
        // 三个都不同 → 最高票 1/3 < 0.5
        var voter = new ScriptedVoter("A", "B", "C");
        var svc = new VotingService(voter, null, props(VotingProperties.Strategy.MAJORITY, 3, 0.5), INLINE);
        var run = svc.vote("q");
        assertEquals(1.0 / 3, run.agreement(), 1e-9);
        assertFalse(run.confident(), "1/3 < 0.5 → 低置信");
    }

    @Test
    void synthesis_callsAggregatorWithAllVotes() {
        var voter = new ScriptedVoter("甲观点", "乙观点");
        // 聚合器把收到的 answers 原样回带，便于断言 N 票都传进去了
        VoteAggregator agg = (question, answers) -> "MERGED::" + answers;
        var svc = new VotingService(voter, agg, props(VotingProperties.Strategy.SYNTHESIS, 2, 0.5), INLINE);
        var run = svc.vote("q");
        assertEquals("synthesis", run.strategy());
        assertTrue(run.decision().startsWith("MERGED::"));
        assertTrue(run.decision().contains("甲观点"));
        assertTrue(run.decision().contains("乙观点"));
        assertTrue(run.confident());
    }

    @Test
    void synthesis_withoutAggregator_fallsBackToFirstVote() {
        var voter = new ScriptedVoter("首票", "次票");
        var svc = new VotingService(voter, null, props(VotingProperties.Strategy.SYNTHESIS, 2, 0.5), INLINE);
        var run = svc.vote("q");
        assertEquals("首票", run.decision(), "未装配聚合器 → 退化取首票");
    }

    @Test
    void explicitN_overridesConfiguredCount() {
        var voter = new ScriptedVoter("x", "x", "x", "x", "x");
        var svc = new VotingService(voter, null, props(VotingProperties.Strategy.MAJORITY, 3, 0.5), INLINE);
        var run = svc.vote("q", 5);
        assertEquals(5, run.votes().size(), "显式 n 覆盖配置的 n");
        assertEquals(1.0, run.agreement(), 1e-9);
    }
}
