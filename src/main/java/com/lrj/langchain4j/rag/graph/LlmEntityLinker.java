package com.lrj.langchain4j.rag.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LLM 实体链接（{@code entity-linking=llm}）：先用 {@link QueryEntityExtractor} 从 query 抽实体提及，
 * 再把提及<strong>锚定到图里真实存在的实体</strong>（归一相等或互为子串）——只返回图中存在的表面形式，
 * 杜绝拿模型臆造的实体名当种子。比 {@link TokenEntityLinker} 更能处理改写/口语化提问，代价是每条 query
 * 多 1 次小 LLM 调用。抽取失败时降级返回空（让向量/keyword 路兜底），不打挂检索。
 */
public class LlmEntityLinker implements EntityLinker {

    private static final Logger log = LoggerFactory.getLogger(LlmEntityLinker.class);

    private final QueryEntityExtractor extractor;
    private final GraphStore store;

    public LlmEntityLinker(QueryEntityExtractor extractor, GraphStore store) {
        this.extractor = extractor;
        this.store = store;
    }

    @Override
    public Set<String> link(String query, String tenant, String category) {
        if (query == null || query.isBlank()) return Set.of();
        List<String> mentions;
        try {
            QueryEntities qe = extractor.extract(query);
            mentions = (qe == null || qe.entities() == null) ? List.of() : qe.entities();
        } catch (Exception e) {
            log.warn("llm entity extraction failed; falling back to no seeds: {}", e.toString());
            return Set.of();
        }
        if (mentions.isEmpty()) return Set.of();

        Set<String> candidates = store.entities(tenant, category);
        if (candidates.isEmpty()) return Set.of();

        Set<String> seeds = new HashSet<>();
        for (String mention : mentions) {
            if (mention == null || mention.isBlank()) continue;
            String mn = norm(mention);
            for (String cand : candidates) {
                if (cand == null) continue;
                String cn = norm(cand);
                // 锚定：归一相等，或互为子串（"张三" ↔ "张三经理"），只收图中真实存在的 cand
                if (cn.equals(mn) || cn.contains(mn) || mn.contains(cn)) {
                    seeds.add(cand.trim());
                }
            }
        }
        return seeds;
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
