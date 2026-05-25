package com.lrj.langchain4j.ai.reflexion;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Stateless answer generator used by {@code ReflexiveService}. Has no memory and no
 * RAG — kept simple so reflection iteration is easy to reason about. If you want
 * the reflection loop to benefit from RAG, swap this for a custom AiService that
 * wires in a ContentRetriever.
 */
public interface Answerer {

    @SystemMessage("""
            You are a careful assistant. Answer the user's question directly,
            concisely, and only based on facts you are confident about.
            """)
    @UserMessage("""
            Question:
            {{it}}
            """)
    String answer(String question);

    @SystemMessage("""
            You are improving a previous answer based on a reviewer's critique.
            Address every issue raised and produce a stronger answer.
            """)
    @UserMessage("""
            QUESTION:
            {{question}}

            PREVIOUS ANSWER:
            {{previous}}

            REVIEWER FEEDBACK:
            {{critique}}

            Provide an improved answer:
            """)
    String improve(@V("question") String question,
                   @V("previous") String previous,
                   @V("critique") String critique);
}
