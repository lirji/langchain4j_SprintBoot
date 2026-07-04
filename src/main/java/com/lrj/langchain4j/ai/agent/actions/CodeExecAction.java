package com.lrj.langchain4j.ai.agent.actions;

import com.lrj.langchain4j.ai.agent.AgentAction;
import com.lrj.langchain4j.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 深度 Agent 动作：让模型写一段 <strong>Java 源码</strong>，在受控沙箱里执行并把 stdout + 表达式求值
 * 结果喂回循环。补齐「模型自己算数 / 转换格式 / 跑确定性逻辑」这类不该靠 LLM 心算、也不值得专门造工具的
 * 长尾计算需求——验证「一个能执行任意代码的动作也能安全地插进 ReAct 循环」（护栏在动作内，循环不感知）。
 *
 * <p><strong>仅 {@code app.deep-agent.enabled} 且 {@code app.deep-agent.code-exec.enabled} 同时为 true 时装配</strong>
 * （双 property 的 {@code @ConditionalOnProperty} 要求全部命中，同 {@link Nl2SqlAction} 的条件装配套路）——
 * 关闭时这个高风险动作根本不出现在可用清单里，模型不会尝试调用。
 *
 * <p>沙箱护栏（{@link CodeExecProperties}）：① 源码长度上限；② 危险 API 静态 denylist（网络/文件/进程/退出，
 * best-effort）；③ 墙钟超时（{@link JShellRunner}）；④ 输出截断。<strong>用 JDK {@link jdk.jshell.JShell}
 * 本地引擎，零新依赖</strong>。局限：JShell local 与宿主同 JVM、Java 21 无 SecurityManager，不是真隔离——
 * 强隔离需外部受限进程/容器（未来项），故默认关且 denylist 兜底。{@link #run} 对超时/超限/编译错误/禁用
 * 一律返回<strong>可纠错文本、绝不抛异常</strong>。
 */
@Component
@ConditionalOnProperty(name = {"app.deep-agent.enabled", "app.deep-agent.code-exec.enabled"}, havingValue = "true")
public class CodeExecAction implements AgentAction {

    private static final Logger log = LoggerFactory.getLogger(CodeExecAction.class);

    /**
     * 危险 API 静态 denylist（小写子串匹配）。尽力而为拦网络/文件/进程/退出/反射逃逸——
     * 不追求完备（同进程执行本就无法真隔离），只挡住模型最容易顺手写出的越界调用。
     */
    private static final List<String> UNSAFE_TOKENS = List.of(
            "java.net", "socket", "url(", "urlconnection", "httpclient", "inetaddress",
            "java.io.file", "fileinputstream", "fileoutputstream", "filewriter", "filereader",
            "randomaccessfile", "java.nio.file", "files.", "paths.",
            "runtime.getruntime", "runtime.exec", "processbuilder", ".exec(",
            "system.exit", "runtime.halt", ".halt(", "shutdown",
            "reflect", "class.forName".toLowerCase(), "getdeclared", "setaccessible",
            "system.load", "loadlibrary", "unsafe");

    private final CodeExecProperties props;

    public CodeExecAction(CodeExecProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "code_exec";
    }

    @Override
    public String description() {
        return "执行一段 Java 代码做精确计算/数据转换/确定性逻辑；actionInput 直接填 Java 源码（可多条语句，"
                + "用表达式或 System.out.println 产出结果）。返回 stdout 与最后表达式的值。"
                + "仅用于计算/转换等纯逻辑，禁止访问网络/文件/进程；需要事实用 rag_search、查库用 nl2sql_query。";
    }

    @Override
    public String run(String input) {
        // —— 运行期兜底判定：Bean 只在双开关命中时装配，但仍显式判一层，便于直接构造测试禁用路径 ——
        if (!props.isEnabled()) {
            return "code_exec 已禁用（需 app.deep-agent.code-exec.enabled=true），请改走其他动作。";
        }
        if (input == null || input.isBlank()) {
            return "代码为空：actionInput 请填要执行的 Java 源码。";
        }
        String source = input.trim();
        if (props.getMaxSourceChars() > 0 && source.length() > props.getMaxSourceChars()) {
            return "代码过长（" + source.length() + " 字符，上限 " + props.getMaxSourceChars()
                    + "），请精简后重试。";
        }
        if (props.isBlockUnsafeApis()) {
            String hit = firstUnsafeToken(source);
            if (hit != null) {
                log.warn("code_exec 拦截疑似越权代码 tenant={} token='{}'", TenantContext.current().tenantId(), hit);
                return "代码被安全策略拦截：检测到疑似受限 API（'" + hit + "'）。"
                        + "code_exec 只允许纯计算/转换，禁止网络/文件/进程/退出等操作。";
            }
        }

        JShellRunner.Outcome outcome;
        try {
            outcome = JShellRunner.run(source, props.getTimeoutMs(), props.getMaxOutputChars());
        } catch (Throwable t) {
            // JShellRunner 已尽量内部消化异常，这里再兜一层保证 run 绝不抛。
            return "执行失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）") + "，请检查代码后重试。";
        }

        if (outcome.timedOut()) {
            return "执行超时（超过 " + props.getTimeoutMs() + "ms 未完成）。"
                    + "避免死循环/长时间阻塞，或减小计算规模后重试。";
        }
        if (outcome.error() != null) {
            String out = outcome.output();
            String prefix = (out == null || out.isBlank()) ? "" : ("已输出：" + out + "\n");
            return prefix + "执行出错：" + outcome.error() + "，请修正代码后重试。";
        }

        String out = outcome.output();
        if (out == null || out.isBlank()) {
            return "执行成功，但没有任何输出。用表达式（如 2+3*4）或 System.out.println(...) 产出结果。";
        }
        if (outcome.truncated()) {
            return out + "\n…（输出超过 " + props.getMaxOutputChars() + " 字符已截断）";
        }
        return out;
    }

    /** 返回命中的第一个危险 token（小写），无命中返回 null。 */
    private static String firstUnsafeToken(String source) {
        String lower = source.toLowerCase();
        for (String token : UNSAFE_TOKENS) {
            if (lower.contains(token)) {
                return token;
            }
        }
        return null;
    }
}
