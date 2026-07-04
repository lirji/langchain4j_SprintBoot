package com.lrj.langchain4j.ai.agent.actions;

import jdk.jshell.Diag;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 用 JDK 内置 {@link JShell} API（{@code jdk.jshell} 模块，<strong>零新依赖</strong>）跑一段 Java 源码，
 * 捕获 stdout/stderr + 每个表达式的求值结果，带墙钟超时与输出截断。给 {@link CodeExecAction} 用。
 *
 * <p>刻意用 <strong>local 执行引擎</strong>（同进程，不 fork 子 JVM、不开 localhost socket）：一是符合
 * 「尽力而为 no network」，二是让单测确定性、不依赖能否 bind 端口 / 起子进程。代价是无法真隔离
 * （snippet 与宿主同 JVM），故 {@link CodeExecAction} 前置一层源码 denylist，且超时只能中断可中断点。
 *
 * <p>超时实现：eval 投到 daemon 线程池，{@link Future#get(long, TimeUnit)} 超时即 {@code cancel(true)}
 * （interrupt 执行线程，能打断 {@code sleep}/IO 等可中断点）+ best-effort {@link JShell#stop()}，
 * <strong>不等待</strong>直接返回超时文本。紧循环（{@code while(true){}}）等不可中断点无法真正杀死——
 * 这是 local 引擎的已知局限（Java 21 已移除 {@code Thread.stop}），线程为 daemon 不挡 JVM 退出。
 */
final class JShellRunner {

    /** 执行结果。error 非空表示编译/运行出错；timedOut 表示超时；truncated 表示输出被截断。 */
    record Outcome(String output, String error, boolean timedOut, boolean truncated) {}

    /** 共享 daemon 线程池：某次 eval 卡住（不可中断）不会阻塞后续 run，且 daemon 不挡 JVM 退出。 */
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "code-exec-jshell");
        t.setDaemon(true);
        return t;
    });

    private JShellRunner() {}

    static Outcome run(String source, long timeoutMs, int maxOutputChars) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        // local 引擎：同进程执行，不 fork、不联网。out/err 汇到同一个缓冲，供模型观察。
        JShell jshell = JShell.builder()
                .out(sink)
                .err(sink)
                .executionEngine("local")
                .build();
        try {
            Future<String> future = POOL.submit(() -> evalAll(jshell, source));
            String values;
            try {
                values = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);           // interrupt 执行线程（打断 sleep/IO 等可中断点）
                bestEffortStop(jshell);        // best-effort，紧循环可能杀不掉（见类注释）
                return finish(captured.toString(StandardCharsets.UTF_8), null, true, maxOutputChars);
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                return finish(captured.toString(StandardCharsets.UTF_8), errText(cause), false, maxOutputChars);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return finish(captured.toString(StandardCharsets.UTF_8), "执行被中断", false, maxOutputChars);
            }
            String stdout = captured.toString(StandardCharsets.UTF_8);
            String combined = stdout;
            if (values != null && !values.isBlank()) {
                combined = combined.isBlank() ? values : (stdout + (stdout.endsWith("\n") ? "" : "\n") + values);
            }
            return finish(combined, null, false, maxOutputChars);
        } catch (Throwable t) {
            // JShell 构建/关闭等意外一律降级为 error 文本，绝不外抛（调用方语义：run 不抛异常）。
            return finish(captured.toString(StandardCharsets.UTF_8), errText(t), false, maxOutputChars);
        } finally {
            try { jshell.close(); } catch (Exception ignore) { /* 关不掉也无所谓，进程内资源 */ }
            sink.close();
        }
    }

    /**
     * 把源码按 JShell 的补全边界拆成一个个 snippet 顺序 eval，收集表达式求值结果字符串。
     * 遇到编译失败（REJECTED）或运行异常，抛 {@link SnippetException} —— 由上层 catch 成 error 文本。
     */
    private static String evalAll(JShell js, String source) {
        StringBuilder values = new StringBuilder();
        SourceCodeAnalysis sca = js.sourceCodeAnalysis();
        String remaining = source;
        int guard = 0;
        while (remaining != null && !remaining.isBlank() && guard++ < 500) {
            CompletionInfo info = sca.analyzeCompletion(remaining);
            String unit = info.source();
            if (unit == null || unit.isBlank()) {
                break; // 剩余不成完整单元（未闭合）——停，交由已 eval 的结果返回
            }
            List<SnippetEvent> events = js.eval(unit);
            for (SnippetEvent e : events) {
                if (e.status() == Snippet.Status.REJECTED) {
                    throw new SnippetException("编译错误：" + diagnostics(js, e.snippet()));
                }
                if (e.exception() != null) {
                    throw new SnippetException("运行时异常：" + shortException(e.exception()));
                }
                if (e.value() != null && !e.value().isEmpty()) {
                    values.append(e.value()).append('\n');
                }
            }
            remaining = info.remaining();
        }
        return values.toString().stripTrailing();
    }

    private static String diagnostics(JShell js, Snippet snippet) {
        try {
            String diags = js.diagnostics(snippet)
                    .map(d -> d.getMessage(Locale.getDefault()))
                    .collect(Collectors.joining("; "));
            return diags.isBlank() ? "语法不合法" : diags;
        } catch (Exception e) {
            return "语法不合法";
        }
    }

    private static String shortException(Exception ex) {
        String msg = ex.getMessage();
        String cls = ex.getClass().getSimpleName();
        return (msg == null || msg.isBlank()) ? cls : cls + ": " + msg;
    }

    private static void bestEffortStop(JShell js) {
        try {
            js.stop();
        } catch (Throwable ignore) {
            // Java 21 local 引擎 stop 对紧循环可能无效，忽略
        }
    }

    private static String errText(Throwable t) {
        if (t instanceof SnippetException) {
            return t.getMessage();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    private static Outcome finish(String out, String error, boolean timedOut, int maxOutputChars) {
        String text = out == null ? "" : out;
        boolean truncated = false;
        if (maxOutputChars > 0 && text.length() > maxOutputChars) {
            text = text.substring(0, maxOutputChars);
            truncated = true;
        }
        return new Outcome(text, error, timedOut, truncated);
    }

    /** 内部信号：源码编译/运行错误。带可读消息，供上层原样转给模型纠错。 */
    static final class SnippetException extends RuntimeException {
        SnippetException(String message) { super(message); }
    }
}
