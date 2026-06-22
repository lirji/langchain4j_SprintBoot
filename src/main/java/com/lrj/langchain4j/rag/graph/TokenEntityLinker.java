package com.lrj.langchain4j.rag.graph;

import com.lrj.langchain4j.rag.hybrid.KeywordTokenizer;

import java.util.HashSet;
import java.util.Set;

/**
 * 零 LLM 的实体链接：拿 (tenant, category) 作用域下的全部实体名当候选，命中 query 即为种子。
 * 复用 hybrid 那条已有的 {@link KeywordTokenizer}（simple / HanLP），不另造分词。
 *
 * <p>两种命中：① <strong>子串包含</strong>（实体表面形式出现在 query 里）—— 对中文实体名最稳；
 * ② <strong>全 token 子集</strong>（实体分词后每个 token 都在 query token 里）—— 兜「订单 1001」/「1001 订单」
 * 这类语序变化。只用这两条精确条件、不做单 token 重叠，避免把高频字误链成种子。
 */
public class TokenEntityLinker implements EntityLinker {

    private static final int MIN_ENTITY_LEN = 2;

    private final GraphStore store;
    private final KeywordTokenizer tokenizer;

    public TokenEntityLinker(GraphStore store, KeywordTokenizer tokenizer) {
        this.store = store;
        this.tokenizer = tokenizer;
    }

    @Override
    public Set<String> link(String query, String tenant, String category) {
        if (query == null || query.isBlank()) return Set.of();
        Set<String> candidates = store.entities(tenant, category);
        if (candidates.isEmpty()) return Set.of();

        Set<String> qTokens = tokenizer.tokenize(query);
        Set<String> seeds = new HashSet<>();
        for (String e : candidates) {
            if (e == null) continue;
            String surface = e.trim();
            if (surface.length() < MIN_ENTITY_LEN) continue;

            if (query.contains(surface)) {            // 子串包含
                seeds.add(surface);
                continue;
            }
            Set<String> eTokens = tokenizer.tokenize(surface);   // 全 token 子集
            if (!eTokens.isEmpty() && qTokens.containsAll(eTokens)) {
                seeds.add(surface);
            }
        }
        return seeds;
    }
}
