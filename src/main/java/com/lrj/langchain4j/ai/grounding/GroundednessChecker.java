package com.lrj.langchain4j.ai.grounding;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * RAG faithfulness 校验员。判定一段答案是否被检索到的 {@code <source>} 片段支撑，
 * 用来在事后拦截"格式完美但内容是编的"事实幻觉。
 *
 * <p>走独立的 temp=0 ChatModel（{@code LlmConfig.buildJudgeChatModel}），跟 {@code Critic} /
 * {@code Judge} 同思路 —— 同一 (sources, answer) 多次判定要给一致分数，否则 warn 闸门会假触发。
 * 由 {@code GroundingConfig} 程序化构造，<strong>不注册 ChatModel Bean</strong>。
 */
public interface GroundednessChecker {

    @SystemMessage("""
            You are a strict RAG faithfulness checker. You are given a set of retrieved
            SOURCES (wrapped in <source id="..."> tags) and an ANSWER. Decide whether the
            ANSWER is grounded in the SOURCES.

            Method (RAGAS-style faithfulness):
            1. Decompose the ANSWER into atomic factual claims — concrete, verifiable
               statements presented as fact.
            2. For each claim, check whether it is directly supported by the SOURCES,
               either verbatim or as a clear paraphrase / logical entailment of the text.
            3. groundedScore = (number of supported claims) / (total number of claims).

            Rules:
            - Judge ONLY substantive factual content. Do NOT count: restatements of the
              user's own question, meta statements about the search ("I found...", "未在
              文档中找到"), greetings, or generic filler.
            - A claim that contradicts or adds information absent from every <source> is
              UNSUPPORTED — list it verbatim (short) in unsupportedClaims.
            - If the ANSWER makes no verifiable factual claims (e.g. it is a refusal,
              an abstention, or pure chit-chat), there is nothing to hallucinate:
              groundedScore = 1.0, unsupportedClaims = [].
            - Common knowledge unrelated to the sources still counts as unsupported if it
              is presented as coming from the documents. Be strict: when unsure whether a
              claim is supported, treat it as unsupported.
            - Do NOT reward or penalize style, completeness, or whether the answer is
              helpful — only grounding in the provided sources.
            """)
    @UserMessage("""
            SOURCES:
            {{sources}}

            ANSWER:
            {{answer}}

            Score how well the ANSWER is grounded in the SOURCES.
            """)
    GroundednessReport check(@V("sources") String sources, @V("answer") String answer);
}
