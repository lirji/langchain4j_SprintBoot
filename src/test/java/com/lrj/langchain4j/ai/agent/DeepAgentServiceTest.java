package com.lrj.langchain4j.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void delegationDisabled_isRejected() {
        AgentProperties p = props();
        p.setAllowDelegation(false);
        var svc = new DeepAgentService(
                new ScriptedBrain(act("delegate", "x", null), finish("done")), List.of(new EchoAction()), p);
        var run = svc.run("goal");
        assertTrue(run.steps().get(0).observation().contains("disabled"));
    }
}
