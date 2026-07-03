package com.lrj.langchain4j.ai.chaining;

/**
 * Prompt Chaining 的一步定义：一条 {@code instruction} + 可选的确定性 <b>gate</b>（步间程序化校验）。
 *
 * <p>gate 是 Anthropic Prompt Chaining 模式的关键——在步与步之间插一道<strong>非 LLM 的确定性检查</strong>，
 * 不达标就<strong>短路终止</strong>整条链（避免把跑偏的中间结果继续喂下去、烧后续 token）。三种 gate 可叠加，
 * 全部为空 = 该步无 gate。用可变 POJO（getter/setter）以便 {@code @ConfigurationProperties} 从 yml 绑定 list。
 */
public class ChainStep {

    /** 步骤名（trace / 日志用）。 */
    private String name = "";
    /** 喂给 {@link ChainLink} 的指令。 */
    private String instruction = "";
    /** gate：输出最小长度（字符）。0 = 关。 */
    private int gateMinLength = 0;
    /** gate：输出必须包含的子串。空/null = 关。 */
    private String gateMustContain;
    /** gate：输出必须命中的正则（find 语义）。空/null = 关。 */
    private String gateMustMatch;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
    public int getGateMinLength() { return gateMinLength; }
    public void setGateMinLength(int gateMinLength) { this.gateMinLength = gateMinLength; }
    public String getGateMustContain() { return gateMustContain; }
    public void setGateMustContain(String gateMustContain) { this.gateMustContain = gateMustContain; }
    public String getGateMustMatch() { return gateMustMatch; }
    public void setGateMustMatch(String gateMustMatch) { this.gateMustMatch = gateMustMatch; }
}
