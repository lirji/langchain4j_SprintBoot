package com.lrj.langchain4j.ai.agent;

/**
 * 深度 Agent 可调用的一个动作（工具）。实现成 Spring {@code @Component} 即被 {@link DeepAgentService}
 * 自动发现并加入可用动作清单——加新能力（RAG 检索 / NL2SQL / 调外部 API…）只需新增一个 Bean，
 * 无需改循环本身。
 *
 * <p>刻意做成「显式 ReAct 动作」而非 LangChain4j 原生 function-calling：循环对每步有完全控制权
 * （预算 / 终止 / 循环检测 / 子 Agent 派生 / 逐步 trace），且结构化决策在所有 provider 上行为一致、
 * 确定性可单测。原生工具调用由主 {@code Assistant} 承担，二者互补。
 */
public interface AgentAction {

    /** 动作名（唯一，大小写不敏感匹配）。模型在结构化决策里按这个名字选动作。 */
    String name();

    /** 给模型看的描述：这个动作干什么、何时该用。是模型「何时调用」的唯一依据，写清楚。 */
    String description();

    /**
     * 执行动作。
     *
     * @param input 模型给的入参（自然语言或查询串）
     * @return 观察结果文本，会被喂回模型作为下一步的依据。<strong>不要抛异常</strong>——
     *         失败请返回可纠错的文本（{@link DeepAgentService} 也会兜底 catch）。
     */
    String run(String input);
}
