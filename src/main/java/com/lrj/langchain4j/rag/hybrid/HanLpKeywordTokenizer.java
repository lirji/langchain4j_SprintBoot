package com.lrj.langchain4j.rag.hybrid;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.dictionary.stopword.CoreStopWordDictionary;
import com.hankcs.hanlp.seg.common.Term;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HanLP-backed tokenizer. Uses the portable dictionary that ships in the JAR, so no
 * external model download is required. Drops stopwords via {@link CoreStopWordDictionary}
 * and single-char Latin tokens; keeps single-char Chinese tokens (HanLP already merges
 * common multi-char terms, so leftover singletons are usually meaningful proper nouns).
 */
public class HanLpKeywordTokenizer implements KeywordTokenizer {

    @Override
    public Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        List<Term> terms = HanLP.segment(text);
        Set<String> out = new HashSet<>(terms.size());
        for (Term t : terms) {
            String word = t.word == null ? "" : t.word.trim().toLowerCase();
            if (word.isEmpty()) continue;
            if (CoreStopWordDictionary.contains(word)) continue;
            // drop pure punctuation / single-char non-Chinese
            if (word.length() == 1 && word.charAt(0) < 0x4E00) continue;
            out.add(word);
        }
        return out;
    }
}
