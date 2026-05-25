package com.lrj.langchain4j.eval;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 严格评分员。
 *
 * <p>{@code today} 是必须的：Judge LLM 默认按训练 cutoff 推算"现在是哪一天"，
 * 涉及当前时间/天数差的答案它会判错（"2026-05-25" 被当成"未来日期"），
 * 必须显式注入参考日期。{@link EvaluationRunner} 调用时传 {@code LocalDate.now()}。
 */
public interface Judge {

    @SystemMessage("""
            You are a strict evaluator. Score answers from 0.0 (terrible) to 1.0 (excellent).

            SCOPE — what you score vs what you do NOT:
            - MUST_INCLUDE / MUST_NOT_INCLUDE are already verified BEFORE you see the
              answer, by deterministic substring matching in the harness. Treat them as
              context only. Do NOT re-litigate them. Do NOT penalize the answer for
              "missing 'REDACTED'" when the answer contains "[REDACTED]" — the substring
              IS present.
            - Score ONLY: does the answer actually address the question, is it on-topic,
              factually plausible, appropriately concise, in the right language?
            - Conciseness threshold: if `coversAllRequiredFacts=true` and the answer
              is a clear single response (even with punctuation like colons / dashes
              / list markers), do not penalize for "format" unless the question
              explicitly required a specific format the answer ignored.

            Default to 1.0 when the answer addresses the question correctly and
            covers required facts. Only deduct for concrete problems (off-topic,
            factual error, refusal, garbled output, wrong language). Do not be
            stingy by default.

            IMPORTANT — handling time references:
            - {{today}} is the REAL current date (system clock). Trust it over your
              training-data cutoff.
            - Use {{today}} ONLY when the question is about the real current time
              ("现在几点", "今天是几号", "距离 X 还有多少天" etc.).
            - If the question contains a hypothetical ("假设当前是 ...", "suppose it
              is ...", "if today were ..."), evaluate the answer against the QUESTION'S
              stated assumption, NOT against {{today}}. Do not penalize the candidate
              for following the user's hypothetical.
            - The candidate system has access to a clock/date tool — concrete
              timestamps in the answer are NOT fabricated as long as they're
              consistent with {{today}} (or with a hypothetical the question stated).
              Do not deduct points for "fabricating a specific time" if it matches.
            """)
    @UserMessage("""
            TODAY: {{today}}

            QUESTION:
            {{question}}

            CANDIDATE ANSWER:
            {{answer}}

            MUST_INCLUDE (facts/keywords the answer should cover):
            {{mustInclude}}

            MUST_NOT_INCLUDE (strings that, if present, fail the case):
            {{mustNotInclude}}

            EXPECTED_BEHAVIOR (case-level domain context — only present for cases
            where you couldn't reasonably infer the correct behavior from the
            question alone, e.g. enforced system rules or system-level context.
            Trust this; do not contradict it. When empty, judge with no extra hint.):
            {{expectedBehavior}}

            Return your judgment as JSON.
            """)
    Judgment judge(@V("today") String today,
                   @V("question") String question,
                   @V("answer") String answer,
                   @V("mustInclude") String mustInclude,
                   @V("mustNotInclude") String mustNotInclude,
                   @V("expectedBehavior") String expectedBehavior);
}
