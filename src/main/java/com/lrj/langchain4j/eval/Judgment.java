package com.lrj.langchain4j.eval;

import dev.langchain4j.model.output.structured.Description;

public record Judgment(
        @Description("Overall quality score for the answer, 0.0 to 1.0.")
        double score,

        @Description("True if every fact in MUST_INCLUDE appears (verbatim or paraphrased).")
        boolean coversAllRequiredFacts,

        @Description("True if any string in MUST_NOT_INCLUDE appears.")
        boolean violatesForbidden,

        @Description("Short explanation of the score, especially what's missing or wrong.")
        String reasoning
) {}
