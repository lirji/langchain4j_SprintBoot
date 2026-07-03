package com.lrj.langchain4j.ai.chaining;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt Chaining 的确定性单测（不连模型）：用脚本化 {@link ChainLink} 顶替 LLM，验证
 * 顺序传递 / 步间 gate 短路 / gate 通过跑完。
 */
class PromptChainServiceTest {

    /** 回显 link：把输出记成 "step<i>(<input>)"，可断言上一步输出确实喂给了下一步。 */
    static class EchoLink implements ChainLink {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String transform(String instruction, String input) {
            return "step" + calls.incrementAndGet() + "(" + input + ")";
        }
    }

    static ChainStep step(String name, String instruction) {
        ChainStep s = new ChainStep();
        s.setName(name);
        s.setInstruction(instruction);
        return s;
    }

    @Test
    void sequential_passesEachOutputToNext() {
        EchoLink link = new EchoLink();
        var svc = new PromptChainService(link);
        var run = svc.run("seed", List.of(step("a", "ia"), step("b", "ib")));
        assertTrue(run.completed());
        assertEquals(2, run.steps().size());
        // 第一步输入是 seed，第二步输入应是第一步输出
        assertEquals("step1(seed)", run.steps().get(0).output());
        assertEquals("step2(step1(seed))", run.steps().get(1).output());
        assertEquals("step2(step1(seed))", run.finalOutput());
    }

    @Test
    void gateMinLength_failsShortCircuits() {
        // link 输出固定短串；第一步 gate 要求 min-length 大 → 短路，第二步不跑
        AtomicInteger secondRan = new AtomicInteger();
        ChainStep a = step("a", "ia");
        a.setGateMinLength(100);
        ChainStep b = step("b", "ib"); // 不该被执行
        var svc = new PromptChainService((i, in) -> {
            if ("ib".equals(i)) secondRan.incrementAndGet();
            return "x";
        });
        var run = svc.run("seed", List.of(a, b));
        assertFalse(run.completed(), "gate 未过 → 整条链短路");
        assertEquals(1, run.steps().size(), "第二步不该执行");
        assertFalse(run.steps().get(0).gatePassed());
        assertTrue(run.steps().get(0).gateReason().contains("过短"));
        assertEquals(0, secondRan.get());
    }

    @Test
    void gateMustContain_passWhenPresent() {
        ChainStep a = step("a", "ia");
        a.setGateMustContain("OK");
        var svc = new PromptChainService((i, in) -> "结果 OK 完成");
        var run = svc.run("seed", List.of(a));
        assertTrue(run.completed());
        assertTrue(run.steps().get(0).gatePassed());
    }

    @Test
    void gateMustMatch_failsWhenNoMatch() {
        ChainStep a = step("a", "ia");
        a.setGateMustMatch("\\d{4}");   // 要求 4 位数字
        var svc = new PromptChainService((i, in) -> "没有数字");
        var run = svc.run("seed", List.of(a));
        assertFalse(run.completed());
        assertTrue(run.steps().get(0).gateReason().contains("未命中模式"));
    }

    @Test
    void badGateRegex_doesNotCrashChain() {
        ChainStep a = step("a", "ia");
        a.setGateMustMatch("(unclosed");   // 坏正则
        var svc = new PromptChainService((i, in) -> "any");
        var run = svc.run("seed", List.of(a));
        // 坏正则被当作未配置该 gate，链正常跑完
        assertTrue(run.completed());
        assertTrue(run.steps().get(0).gatePassed());
    }
}
