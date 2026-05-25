package com.lrj.langchain4j.rag.hybrid;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Crude but dependency-free tokenizer: splits on whitespace / punctuation and treats every
 * Chinese character as its own token. Good enough as a fallback when HanLP is unavailable;
 * recall on multi-char Chinese terms is bad.
 */
public class SimpleKeywordTokenizer implements KeywordTokenizer {

    private static final Pattern SPLIT =
            Pattern.compile("[\\s\\p{Punct}\\p{IsHan}—…]+|(?<=\\p{IsHan})(?=\\p{IsHan})");

    @Override
    public Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(SPLIT.split(text.toLowerCase()))
                .filter(t -> !t.isBlank() && t.length() > 1)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
