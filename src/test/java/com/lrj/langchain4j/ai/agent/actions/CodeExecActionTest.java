package com.lrj.langchain4j.ai.agent.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeExecAction} 的确定性单测：真实跑 JDK {@link jdk.jshell.JShell}（local 引擎，<strong>不联网、
 * 不 fork 子进程</strong>），但只依赖表达式求值这条稳定路径断言，且每条都设兜底超时——无网络、可重复。
 *
 * <p>覆盖：算术表达式求值正确 / 输出超限截断 / 死等超时被兜住 / 编译错误可纠错 / 危险 API 静态拦截 /
 * 源码超长拒绝 / 禁用开关路径 / 空入参守卫。所有失败路径都验证 {@link CodeExecAction#run} 返回可纠错文本
 * 而非抛异常。
 */
class CodeExecActionTest {

    /** 造一份开启的护栏配置。 */
    private static CodeExecProperties enabledProps(long timeoutMs, int maxOutputChars) {
        CodeExecProperties p = new CodeExecProperties();
        p.setEnabled(true);
        p.setTimeoutMs(timeoutMs);
        p.setMaxOutputChars(maxOutputChars);
        return p;
    }

    private static CodeExecAction action(long timeoutMs, int maxOutputChars) {
        return new CodeExecAction(enabledProps(timeoutMs, maxOutputChars));
    }

    @Test
    void metadata_isStable() {
        CodeExecAction a = action(20_000, 2000);
        assertEquals("code_exec", a.name());
        assertTrue(a.description().contains("Java"), "描述应说明填 Java 源码");
    }

    @Test
    void arithmeticExpression_returnsCorrectResult() {
        // 表达式求值路径：JShell 把 2+3*4 的值 14 作为 snippet value 返回，引擎无关、最稳。
        String out = action(20_000, 2000).run("2 + 3 * 4");
        assertTrue(out.contains("14"), "算术结果应为 14，实际：" + out);
        assertFalse(out.contains("出错"), "正常算术不应报错：" + out);
    }

    @Test
    void oversizeOutput_isTruncated() {
        // 值长度 ~52（带引号），上限设 10 → 必被截断。
        String out = action(20_000, 10).run("\"X\".repeat(50)");
        assertTrue(out.contains("截断"), "超限应带截断标记，实际：" + out);
        assertTrue(out.contains("X"), "截断后仍应保留前缀内容");
    }

    @Test
    void runawaySnippet_timesOut() {
        // Thread.sleep 远大于超时 → 触发墙钟超时并返回可纠错文本（sleep 可中断，线程随后释放）。
        String out = action(400, 2000).run("Thread.sleep(60000)");
        assertTrue(out.contains("超时"), "死等应超时兜住，实际：" + out);
    }

    @Test
    void compileError_returnsCorrectableText() {
        String out = action(20_000, 2000).run("int x = ;");
        assertTrue(out.contains("出错") || out.contains("编译"), "编译错误应可纠错，实际：" + out);
    }

    @Test
    void unsafeApi_isBlockedStatically() {
        String out = action(20_000, 2000).run("new java.net.Socket(\"example.com\", 80)");
        assertTrue(out.contains("拦截"), "网络 API 应被静态拦截，实际：" + out);
        assertFalse(out.contains("14"), "被拦截时不应执行");
    }

    @Test
    void sourceTooLong_isRejected() {
        CodeExecProperties p = enabledProps(20_000, 2000);
        p.setMaxSourceChars(20);
        String out = new CodeExecAction(p).run("1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1");
        assertTrue(out.contains("过长"), "超长源码应被拒绝，实际：" + out);
    }

    @Test
    void disabledFlag_returnsDisabledHint() {
        CodeExecProperties p = new CodeExecProperties(); // enabled 默认 false
        String out = new CodeExecAction(p).run("2 + 2");
        assertTrue(out.contains("已禁用"), "禁用路径应提示，实际：" + out);
    }

    @Test
    void blankInput_returnsCorrectableHint() {
        String out = action(20_000, 2000).run("   ");
        assertTrue(out.contains("为空"), "空入参应有守卫，实际：" + out);
    }
}
