package com.lrj.langchain4j.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 深度 Agent 循环的确定性单测（不连模型）：用脚本化 {@link AgentBrain} 喂预设决策，验证终止/循环检测/
 * 未知动作恢复/scratchpad/委派。脚本 brain 每次调用按顺序吐下一个决策。
 */
class DeepAgentServiceTest {

    /** 脚本化 brain：按队列顺序返回预设决策；队列空了就 finish 兜底。 */
    static class ScriptedBrain implements AgentBrain {
        final Deque<AgentDecision> script = new ArrayDeque<>();
        final List<String> seenScratchpads = new ArrayList<>();
        ScriptedBrain(AgentDecision... decisions) {
            for (AgentDecision d : decisions) script.add(d);
        }
        @Override public AgentDecision decide(String goal, String actions, String scratchpad, String history) {
            seenScratchpads.add(scratchpad);
            AgentDecision d = script.poll();
            return d != null ? d : new AgentDecision("done", "finish", "", "", "fallback");
        }
    }

    static AgentDecision act(String action, String input, String note) {
        return new AgentDecision("t", action, input, note, "");
    }
    static AgentDecision finish(String answer) {
        return new AgentDecision("t", "finish", "", "", answer);
    }

    /** 计数 + 回显的动作。 */
    static class EchoAction implements AgentAction {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String name() { return "echo"; }
        @Override public String description() { return "echo input"; }
        @Override public String run(String input) { calls.incrementAndGet(); return "echo:" + input; }
    }

    static AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.setMaxSteps(5);
        p.setMaxRepeats(3);
        p.setAllowDelegation(true);
        p.setMaxDepth(1);
        return p;
    }

    @Test
    void finishOnFirstStep_returnsDone() {
        var svc = new DeepAgentService(new ScriptedBrain(finish("42")), List.of(new EchoAction()), props());
        var run = svc.run("what is the answer?");
        assertEquals("DONE", run.stopReason());
        assertEquals("42", run.finalAnswer());
        assertTrue(run.steps().isEmpty(), "finish on step 1 records no action steps");
    }

    @Test
    void actionThenFinish_executesAndObserves() {
        EchoAction echo = new EchoAction();
        var svc = new DeepAgentService(
                new ScriptedBrain(act("echo", "hi", null), finish("done")), List.of(echo), props());
        var run = svc.run("goal");
        assertEquals("DONE", run.stopReason());
        assertEquals(1, echo.calls.get());
        assertEquals(1, run.steps().size());
        assertEquals("echo:hi", run.steps().get(0).observation());
    }

    @Test
    void unknownAction_recoverable_thenFinish() {
        var svc = new DeepAgentService(
                new ScriptedBrain(act("nope", "x", null), finish("ok")), List.of(new EchoAction()), props());
        var run = svc.run("goal");
        assertEquals("DONE", run.stopReason());
        assertTrue(run.steps().get(0).observation().contains("unknown action"),
                "unknown action returns a recoverable observation, not a crash");
    }

    @Test
    void neverFinishes_hitsMaxSteps() {
        // 每步都给不同入参（避开循环检测），跑满 maxSteps
        ScriptedBrain brain = new ScriptedBrain(
                act("echo", "a", null), act("echo", "b", null), act("echo", "c", null),
                act("echo", "d", null), act("echo", "e", null));
        var svc = new DeepAgentService(brain, List.of(new EchoAction()), props());
        var run = svc.run("goal");
        assertEquals("MAX_STEPS", run.stopReason());
        assertEquals(5, run.steps().size());
    }

    @Test
    void repeatedAction_detectedAsLoop() {
        // 连续 3 次相同 (动作,入参) → LOOP
        var svc = new DeepAgentService(
                new ScriptedBrain(act("echo", "same", null), act("echo", "same", null), act("echo", "same", null)),
                List.of(new EchoAction()), props());
        var run = svc.run("goal");
        assertEquals("LOOP", run.stopReason());
    }

    @Test
    void scratchpadNote_accumulatesAcrossSteps() {
        ScriptedBrain brain = new ScriptedBrain(
                act("echo", "x", "结论A"), finish("done"));
        var svc = new DeepAgentService(brain, List.of(new EchoAction()), props());
        svc.run("goal");
        // 第二次 decide 看到的 scratchpad 应含第一步记的 note
        assertTrue(brain.seenScratchpads.get(1).contains("结论A"),
                "note from step 1 must be in the scratchpad re-injected at step 2");
    }

    @Test
    void delegate_runsSubAgent_andRespectsDepthCap() {
        // 顶层第一步 delegate；子 Agent（depth=1, 已达 maxDepth）里再 delegate 应被拒，然后 finish
        ScriptedBrain brain = new ScriptedBrain(
                act("delegate", "sub goal", null),   // depth 0 → 派生
                act("delegate", "deeper", null),      // depth 1 → 被拒
                finish("sub done"),                    // 子 Agent 收尾
                finish("top done"));                   // 顶层收尾
        var svc = new DeepAgentService(brain, List.of(new EchoAction()), props());
        var run = svc.run("top goal");
        assertEquals("DONE", run.stopReason());
        assertEquals("top done", run.finalAnswer());
        // 顶层第一步的观察来自子 Agent 的最终答案
        assertTrue(run.steps().get(0).observation().contains("sub done"));
        // 子 Agent 内那步 delegate 被深度上限拒绝
        assertTrue(run.steps().get(0).observation().contains("[sub-agent"));
    }

    /** 实现 AgentRunListener 的动作：记录 onRunEnd 被回调几次。 */
    static class ClosableAction implements AgentAction, AgentRunListener {
        int closed = 0;
        @Override public String name() { return "closable"; }
        @Override public String description() { return "stub"; }
        @Override public String run(String input) { return "ran"; }
        @Override public void onRunEnd() { closed++; }
    }

    @Test
    void onRunEnd_calledOnceAfterTopLevelRun() {
        ClosableAction action = new ClosableAction();
        var svc = new DeepAgentService(
                new ScriptedBrain(act("closable", "x", null), finish("done")), List.of(action), props());
        svc.run("goal");
        assertEquals(1, action.closed, "AgentRunListener.onRunEnd fires once when the top-level run ends");
    }

    @Test
    void onRunEnd_calledEvenWhenBrainThrows() {
        ClosableAction action = new ClosableAction();
        AgentBrain boom = (g, a, s, h) -> { throw new RuntimeException("kaboom"); };
        var svc = new DeepAgentService(boom, List.of(action), props());
        var run = svc.run("goal");
        assertEquals("ERROR", run.stopReason());
        assertEquals(1, action.closed, "cleanup must run even if the brain blows up");
    }

    @Test
    void interruptedThread_stopsWithCancelled() {
        EchoAction echo = new EchoAction();
        // brain 第一步返回动作并把当前线程标记为 interrupted（模拟异步 Future.cancel(true)）；
        // 第二步开头侦测到中断 → CANCELLED。第一步照常跑完（无法中止已在进行的步骤）。
        AgentBrain interrupting = new ScriptedBrain(act("echo", "x", null), act("echo", "y", null)) {
            @Override public AgentDecision decide(String g, String a, String s, String h) {
                AgentDecision d = super.decide(g, a, s, h);
                Thread.currentThread().interrupt();
                return d;
            }
        };
        var run = new DeepAgentService(interrupting, List.of(echo), props()).run("goal");
        assertEquals("CANCELLED", run.stopReason());
        assertEquals(1, echo.calls.get(), "进行中的那一步跑完，下一步前才停");
        assertFalse(Thread.currentThread().isInterrupted(), "顶层 run 收尾应清掉中断标志，避免污染线程池");
    }

    @Test
    void wallClockBudget_exceeded_stopsWithTimeout() {
        EchoAction echo = new EchoAction();
        AgentProperties p = props();
        p.setMaxWallClockMs(100);
        // clock 调用序: #1 算 deadline(0+100), #2 step1 检查(0<100 放行), #3 step2 检查(200>=100 → TIMEOUT)
        long[] times = {0L, 0L, 200L};
        AtomicInteger idx = new AtomicInteger();
        LongSupplier clock = () -> times[Math.min(idx.getAndIncrement(), times.length - 1)];
        var svc = new DeepAgentService(
                new ScriptedBrain(act("echo", "a", null), act("echo", "b", null)),
                List.of(echo), p, null, clock, s -> 0);
        var run = svc.run("goal");
        assertEquals("TIMEOUT", run.stopReason());
        assertEquals(1, echo.calls.get(), "超时前跑完的那一步保留，下一步前才停");
    }

    @Test
    void tokenBudget_exceeded_stopsWithBudget() {
        EchoAction echo = new EchoAction();
        AgentProperties p = props();
        p.setMaxTokens(6);   // 估算器每次记 1；每步消耗 5(输入+决策) + 1(观察) = 6 → step2 开头即超
        var svc = new DeepAgentService(
                new ScriptedBrain(act("echo", "a", null), act("echo", "b", null), act("echo", "c", null)),
                List.of(echo), p, null, () -> 0L, s -> 1);
        var run = svc.run("goal");
        assertEquals("BUDGET", run.stopReason());
        assertEquals(1, echo.calls.get(), "预算耗尽前跑完 step1，step2 开头判 BUDGET");
    }

    @Test
    void oscillatingActions_detectedAsLoop() {
        // A→B→A→B→A：从无 3 次「连续」相同，旧逻辑抓不到；滑窗内 A 出现 3 次 → LOOP
        var svc = new DeepAgentService(
                new ScriptedBrain(act("echo", "A", null), act("echo", "B", null),
                        act("echo", "A", null), act("echo", "B", null), act("echo", "A", null)),
                List.of(new EchoAction()), props());
        var run = svc.run("goal");
        assertEquals("LOOP", run.stopReason());
    }

    @Test
    void scratchpadOverflow_lineAware_dropsOldestWholeLine() {
        AgentProperties p = props();
        p.setMaxScratchpadChars(45);   // 无摘要器 → 溢出丢弃最旧整条，不腰斩半行
        ScriptedBrain brain = new ScriptedBrain(
                act("echo", "1", "oldest-" + "x".repeat(30)),
                act("echo", "2", "newest-" + "y".repeat(30)),
                finish("done"));
        var svc = new DeepAgentService(brain, List.of(new EchoAction()), p);
        svc.run("goal");
        String seen = brain.seenScratchpads.get(2);   // 第 3 次 decide 看到压缩后的 scratchpad
        assertTrue(seen.contains("newest"), "最新结论完整保留");
        assertFalse(seen.contains("oldest"), "最旧结论被整条丢弃");
        for (String line : seen.split("\n")) {
            assertTrue(line.isBlank() || line.startsWith("- "), "不该腰斩半行: <" + line + ">");
        }
    }

    @Test
    void scratchpadOverflow_withSummarizer_compactsOldestIntoSummary() {
        AgentProperties p = props();
        p.setMaxScratchpadChars(30);
        p.setScratchpadSummary(true);
        ScratchpadSummarizer summarizer = notes -> "SUM";   // 把挤出的旧结论压成一条
        ScriptedBrain brain = new ScriptedBrain(
                act("echo", "1", "A".repeat(10)),
                act("echo", "2", "B".repeat(10)),
                act("echo", "3", "C".repeat(10)),
                finish("done"));
        var svc = new DeepAgentService(brain, List.of(new EchoAction()), p, summarizer);
        svc.run("goal");
        String seen = brain.seenScratchpads.get(3);   // 第 4 次 decide 看到含摘要的 scratchpad
        assertTrue(seen.contains("早期结论摘要"), "旧结论压成一条摘要 bullet");
        assertTrue(seen.contains("SUM"), "摘要内容注入");
        assertTrue(seen.contains("C".repeat(10)), "最新结论仍完整保留");
    }

    @Test
    void brainTransientFailure_retriedThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        AgentBrain flaky = (g, a, s, h) -> {
            if (calls.getAndIncrement() == 0) throw new RuntimeException("transient parse fail");
            return new AgentDecision("t", "finish", "", "", "recovered");
        };
        AgentProperties p = props();
        p.setBrainMaxRetries(1);
        var run = new DeepAgentService(flaky, List.of(new EchoAction()), p).run("goal");
        assertEquals("DONE", run.stopReason());
        assertEquals("recovered", run.finalAnswer());
        assertEquals(2, calls.get(), "第一次失败、重试第二次成功");
    }

    @Test
    void brainRetriesExhausted_stopsWithError() {
        AtomicInteger calls = new AtomicInteger();
        AgentBrain boom = (g, a, s, h) -> { calls.incrementAndGet(); throw new RuntimeException("always"); };
        AgentProperties p = props();
        p.setBrainMaxRetries(2);
        var run = new DeepAgentService(boom, List.of(new EchoAction()), p).run("goal");
        assertEquals("ERROR", run.stopReason());
        assertEquals(3, calls.get(), "1 次初始 + 2 次重试 = 3 次后放弃");
    }

    @Test
    void delegationDisabled_isRejected() {
        AgentProperties p = props();
        p.setAllowDelegation(false);
        var svc = new DeepAgentService(
                new ScriptedBrain(act("delegate", "x", null), finish("done")), List.of(new EchoAction()), p);
        var run = svc.run("goal");
        assertTrue(run.steps().get(0).observation().contains("disabled"));
    }
}
