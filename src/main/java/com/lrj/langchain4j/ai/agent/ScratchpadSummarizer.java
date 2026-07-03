package com.lrj.langchain4j.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * scratchpad 溢出时把「被挤出的最旧结论」压成一条精炼摘要，保住信息又不撑爆 prompt。
 *
 * <p>只在 {@code app.deep-agent.scratchpad-summary=true} 时装配（{@code DeepAgentConfig} 条件化）；
 * 关闭（默认）时 {@link DeepAgentService} 拿不到本 Bean，scratchpad 溢出退化为「按 bullet 行丢弃最旧」
 * 的确定性截断——零 LLM 开销、零回归。
 *
 * <p>走独立 temp=0 ChatModel（{@code buildJudgeChatModel}，压缩是确定性任务，避免每次压出不同摘要导致
 * 工作记忆漂移），与 {@code SummarizingChatMemory} 摘要器同思路。摘要在循环线程内<strong>同步</strong>调用
 * （保 {@code TenantContext} → token 正确计入租户配额）；失败由调用方兜底降级为丢弃，不让循环崩。
 */
public interface ScratchpadSummarizer {

    @SystemMessage("""
            你在压缩一个自主 Agent 的工作记忆。下面是它早期记下的一批结论/计划（bullet 列表），
            现因空间不足需要压缩。请把它们浓缩成 2-4 条最关键的**事实性结论**，供后续步骤继续参考。

            规则：
            - 只保留对达成目标仍有用的持久结论，丢弃临时的、已被后续覆盖的、无信息量的条目。
            - 保留具体的数值、名称、id、引用标记（如 [doc=xxx]），不要泛化成空话。
            - 输出精炼中文，一行一条，不要编号、不要寒暄、不要复述本指令。
            """)
    @UserMessage("""
            早期结论：
            {{notes}}

            压缩后的关键结论：
            """)
    String summarize(@V("notes") String notes);
}
