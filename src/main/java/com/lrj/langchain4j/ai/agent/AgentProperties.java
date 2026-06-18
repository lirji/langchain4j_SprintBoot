package com.lrj.langchain4j.ai.agent;

/**
 * {@code app.deep-agent.*} 绑定。<strong>默认关</strong> → 深度 Agent 相关 Bean 全不装配
 * （{@code DeepAgentConfig} / {@code AgentController} 条件化在 {@code app.deep-agent.enabled=true}）。
 *
 * <p>深度 Agent 是开放式 <strong>plan → act → observe</strong> 循环（区别于 {@code multiagent} 的
 * 固定 DAG：plan→并行 worker→synthesize 一锤子）。每步由 {@link AgentBrain} 结构化决策选一个动作，
 * 编排器执行并把观察喂回，直到 {@code finish} 或预算耗尽。
 */
public class AgentProperties {

    /** 总开关。关闭（默认）时整个深度 Agent 链不装配。 */
    private boolean enabled = false;
    /** 单次 run 的最大步数（硬预算，挡 runaway 循环）。 */
    private int maxSteps = 8;
    /** 连续重复同一 (动作,入参) 达到此次数 → 判定卡死循环，提前终止。 */
    private int maxRepeats = 3;
    /** scratchpad（跨步工作记忆）字符上限，超出截断，防多步累积越滚越大撑爆 prompt。 */
    private int maxScratchpadChars = 4000;
    /** 注入 prompt 的最近步数（history 窗口），控制每步 prompt 体积。 */
    private int historyWindow = 6;
    /** 是否允许 delegate 动作（派生子 Agent 跑子目标）。 */
    private boolean allowDelegation = true;
    /** delegate 的最大递归深度（0 = 顶层不可再派生）。挡无限自我派生。 */
    private int maxDepth = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public int getMaxRepeats() { return maxRepeats; }
    public void setMaxRepeats(int maxRepeats) { this.maxRepeats = maxRepeats; }
    public int getMaxScratchpadChars() { return maxScratchpadChars; }
    public void setMaxScratchpadChars(int maxScratchpadChars) { this.maxScratchpadChars = maxScratchpadChars; }
    public int getHistoryWindow() { return historyWindow; }
    public void setHistoryWindow(int historyWindow) { this.historyWindow = historyWindow; }
    public boolean isAllowDelegation() { return allowDelegation; }
    public void setAllowDelegation(boolean allowDelegation) { this.allowDelegation = allowDelegation; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
}
