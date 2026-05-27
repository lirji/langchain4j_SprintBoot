package com.lrj.langchain4j.ai.reflexion;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Stateless answer generator used by {@code ReflexiveService}. Has no memory and no
 * RAG — kept simple so reflection iteration is easy to reason about. If you want
 * the reflection loop to benefit from RAG, swap this for a custom AiService that
 * wires in a ContentRetriever.
 *
 * <p>Stream 变种（{@code answerStream} / {@code improveStream}）需要 {@code ReflexionConfig}
 * 给 Answerer builder 额外传 {@code streamingChatModel} 才会生效。
 */
public interface Answerer {

    String ANSWER_SYSTEM = """
            You are a careful assistant. Answer the user's question directly,
            concisely, and only based on facts you are confident about.
            """;

    String ANSWER_USER = """
            Question:
            {{it}}
            """;

    String IMPROVE_SYSTEM = """
            You are improving a previous answer based on a reviewer's critique.
            Address every issue raised and produce a stronger answer.
            """;

    String IMPROVE_USER = """
            QUESTION:
            {{question}}

            PREVIOUS ANSWER:
            {{previous}}

            REVIEWER FEEDBACK:
            {{critique}}

            Provide an improved answer:
            """;

    @SystemMessage(ANSWER_SYSTEM)
    @UserMessage(ANSWER_USER)
    String answer(String question);

    @SystemMessage(ANSWER_SYSTEM)
    @UserMessage(ANSWER_USER)
    TokenStream answerStream(String question);

    @SystemMessage(IMPROVE_SYSTEM)
    @UserMessage(IMPROVE_USER)
    String improve(@V("question") String question,
                   @V("previous") String previous,
                   @V("critique") String critique);

    @SystemMessage(IMPROVE_SYSTEM)
    @UserMessage(IMPROVE_USER)
    TokenStream improveStream(@V("question") String question,
                              @V("previous") String previous,
                              @V("critique") String critique);
}
