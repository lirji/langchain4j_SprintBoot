package com.lrj.langchain4j.ai.voting;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Voting 的 {@code synthesis} 聚合策略：把 N 个独立回答交给一个聚合者 LLM 收口成共识答案
 * （自由文本题用；离散/分类题用确定性 {@code majority} 多数表决，不需要本接口）。
 *
 * <p>走独立 temp=0 ChatModel（{@code buildJudgeChatModel}，聚合是确定性收敛任务），不注册 ChatModel Bean，
 * 与 {@code Critic}/{@code Judge}/{@code Synthesizer} 同思路。
 */
public interface VoteAggregator {

    @SystemMessage("""
            下面是多个 AI 对**同一个问题**的独立回答。请综合它们，输出一个最可靠的**共识答案**：
            - 多数一致的结论优先采纳；
            - 若存在明显分歧，指出主流观点并简要说明分歧；
            - 只输出最终答案，不要罗列每个回答、不要寒暄。
            """)
    @UserMessage("""
            问题：{{question}}

            多个独立回答：
            {{answers}}
            """)
    String merge(@V("question") String question, @V("answers") String answers);
}
