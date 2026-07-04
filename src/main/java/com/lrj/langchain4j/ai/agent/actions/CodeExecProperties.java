package com.lrj.langchain4j.ai.agent.actions;

/**
 * {@code app.deep-agent.code-exec.*} 绑定。<strong>默认关</strong>——{@link CodeExecAction} 与本
 * properties Bean 仅在 {@code app.deep-agent.enabled} 且 {@code app.deep-agent.code-exec.enabled}
 * 同时为 true 时装配（见 {@link CodeExecConfig}）。
 *
 * <p>受控 Java 代码沙箱的护栏参数：墙钟超时 / 输出上限 / 源码长度上限 / 危险 API 静态拦截开关。
 * 这些默认值刻意保守——code_exec 是「让模型写代码并执行」的高风险能力，宁可小而稳。
 */
public class CodeExecProperties {

    /** 子开关（父开关是 {@code app.deep-agent.enabled}）。关闭时动作不装配，运行期再兜一层判定。 */
    private boolean enabled = false;
    /** 单次执行的墙钟超时（毫秒）。超时即中断并返回可纠错文本，绝不无限等。 */
    private long timeoutMs = 3000;
    /** 回传给模型的输出（stdout + 表达式求值）字符上限，超出截断并加标记，防 scratchpad 爆。 */
    private int maxOutputChars = 2000;
    /** 允许提交的源码字符上限，超出直接拒绝（挡超大 payload / 上下文轰炸）。 */
    private int maxSourceChars = 4000;
    /**
     * 是否静态拦截危险 API（网络 / 文件 / 进程 / System.exit / 反射逃逸）。默认 true。
     * 说明：JShell 本地执行引擎与宿主 JVM 同进程、Java 21 已无 SecurityManager，无法做到真沙箱；
     * 这里只做<strong>尽力而为</strong>的源码 denylist 静态拦截。真正强隔离需外部受限进程 / 容器（未来项）。
     */
    private boolean blockUnsafeApis = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxOutputChars() { return maxOutputChars; }
    public void setMaxOutputChars(int maxOutputChars) { this.maxOutputChars = maxOutputChars; }
    public int getMaxSourceChars() { return maxSourceChars; }
    public void setMaxSourceChars(int maxSourceChars) { this.maxSourceChars = maxSourceChars; }
    public boolean isBlockUnsafeApis() { return blockUnsafeApis; }
    public void setBlockUnsafeApis(boolean blockUnsafeApis) { this.blockUnsafeApis = blockUnsafeApis; }
}
