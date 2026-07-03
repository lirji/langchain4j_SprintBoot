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
    /**
     * 单次 run 的墙钟预算（毫秒）。>0 时每步开头若已超时 → 判 {@code TIMEOUT} 终止。
     * 0（默认）= 关。补步数预算的短板：单步 LLM/动作偶发慢时，纯步数上限挡不住耗时跑飞。
     */
    private long maxWallClockMs = 0;
    /**
     * 单次 run 的近似 token 预算。>0 时累计（每步 prompt 输入 + 决策 + 观察的估算 token）超阈 →
     * 判 {@code BUDGET} 终止。0（默认）= 关。这是<strong>循环内</strong>的安全上限（软界，会略微
     * 超出最后一步），与全局 per-tenant 日配额（{@code ChatModelListener}）正交。估算走字符/4 启发式，
     * 非精确计费，只为挡「上下文越滚越大烧到全局配额上限才停」。
     */
    private int maxTokens = 0;
    /** 连续重复同一 (动作,入参) 达到此次数 → 判定卡死循环，提前终止。 */
    private int maxRepeats = 3;
    /**
     * 循环检测的滑窗大小（最近多少步内计同一 (动作,入参) 的出现次数）。窗口内出现达 {@code maxRepeats} 次
     * → 判 {@code LOOP}。&gt; 连续检测：能抓 A→B→A→B 这种震荡（旧逻辑只抓「连续完全相同」）。
     * 实际生效窗口取 {@code max(loopWindow, maxRepeats)}。
     */
    private int loopWindow = 6;
    /** scratchpad（跨步工作记忆）字符上限，超出压缩，防多步累积越滚越大撑爆 prompt。 */
    private int maxScratchpadChars = 4000;
    /**
     * scratchpad 溢出时是否用 LLM 摘要压缩最旧结论（需装配 {@code ScratchpadSummarizer} Bean）。
     * false（默认）= 按 bullet 行丢弃最旧的整条（line-aware，不再腰斩半行）。true = 把挤出的旧结论
     * 交给摘要器压成一条摘要 bullet 保住信息，失败降级为丢弃。
     */
    private boolean scratchpadSummary = false;
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
    public long getMaxWallClockMs() { return maxWallClockMs; }
    public void setMaxWallClockMs(long maxWallClockMs) { this.maxWallClockMs = maxWallClockMs; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getMaxRepeats() { return maxRepeats; }
    public void setMaxRepeats(int maxRepeats) { this.maxRepeats = maxRepeats; }
    public int getLoopWindow() { return loopWindow; }
    public void setLoopWindow(int loopWindow) { this.loopWindow = loopWindow; }
    public int getMaxScratchpadChars() { return maxScratchpadChars; }
    public void setMaxScratchpadChars(int maxScratchpadChars) { this.maxScratchpadChars = maxScratchpadChars; }
    public boolean isScratchpadSummary() { return scratchpadSummary; }
    public void setScratchpadSummary(boolean scratchpadSummary) { this.scratchpadSummary = scratchpadSummary; }
    public int getHistoryWindow() { return historyWindow; }
    public void setHistoryWindow(int historyWindow) { this.historyWindow = historyWindow; }
    public boolean isAllowDelegation() { return allowDelegation; }
    public void setAllowDelegation(boolean allowDelegation) { this.allowDelegation = allowDelegation; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
}
