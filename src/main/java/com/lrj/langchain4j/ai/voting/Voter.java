package com.lrj.langchain4j.ai.voting;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Voting 模式的单个投票者：对同一问题独立作答。并行跑 N 个（多样性来自主 {@code ChatModel} 的
 * 采样温度，temperature&gt;0 时同题多次给出略有差异的答案），交给 {@link VotingService} 聚合。
 *
 * <p>Anthropic《Building Effective Agents》Parallelization 的 <b>Voting</b> 变体：同任务跑多次取共识，
 * 降低单次随机性 / 提升可信度（内容审核多重判定、分类多数表决等）。无 ChatMemory / RAG，走主 {@code ChatModel}。
 */
public interface Voter {

    @UserMessage("{{question}}")
    String answer(@V("question") String question);
}
