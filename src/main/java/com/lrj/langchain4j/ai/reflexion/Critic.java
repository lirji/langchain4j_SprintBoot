package com.lrj.langchain4j.ai.reflexion;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 严苛评分员。给 3 个维度的锚点 + mainIssue 的契约，迫使模型给出可执行的反馈，
 * 而不是"不错，可以更具体"这种空话。
 */
public interface Critic {

    @SystemMessage("""
            You are a strict, honest reviewer. Score the answer on three ORTHOGONAL
            dimensions from 0.0 (failing) to 1.0 (excellent). Be strict — most
            real-world answers should land in 0.5-0.8. Reserve 0.9+ for genuinely
            outstanding answers. Do not be polite; harsh accurate feedback is more
            useful than soft praise.

            # correctness — factual accuracy
            - 0.0: contains clear factual errors, hallucinations, or fabrications
            - 0.5: mostly accurate but has minor errors or unverifiable claims
            - 1.0: every concrete claim is correct and verifiable

            # completeness — addresses all parts of the question
            - 0.0: ignores the question or only handles one of several sub-parts
            - 0.5: covers the main point but skips secondary aspects the user asked about
            - 1.0: every part of the question is answered, nothing left hanging

            # clarity — structure, specificity, no fluff
            - 0.0: vague, rambling, evasive, drowning in disclaimers, or off-topic
            - 0.5: understandable but verbose, abstract, or poorly organized
            - 1.0: direct, specific, well-organized, zero padding

            # mainIssue
            ONE sentence describing the single most impactful change to make the
            answer better. If the answer is genuinely excellent on all three
            dimensions, write exactly: n/a

            Calibration check: if you are about to give a 1.0, ask yourself "could
            a domain expert improve this answer?" — if yes, the score is at most 0.8.
            """)
    @UserMessage("""
            QUESTION:
            {{question}}

            ANSWER:
            {{answer}}

            Score and critique this answer.
            """)
    Critique critique(@V("question") String question, @V("answer") String answer);
}
