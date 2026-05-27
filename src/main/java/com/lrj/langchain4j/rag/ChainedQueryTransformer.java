package com.lrj.langchain4j.rag;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 把多个 {@link QueryTransformer} 按顺序串联：前一个的输出（{@code Collection<Query>}）逐个
 * 喂给下一个，结果 flat 起来。
 *
 * <p>典型用法：先 {@code CompressingQueryTransformer}（把多轮对话压成 self-contained query）
 * 再 {@code ExpandingQueryTransformer}（1 → N 个变体）。LangChain4j 1.13 的
 * {@code RetrievalAugmentor} 只接一个 QueryTransformer，所以自己写这个 chain 才能两个都用上。
 *
 * <p>顺序敏感：compress 必须在 expand 前 —— 不然 expander 看到的是带代词的原 query，扩出 N
 * 个一样有歧义的变体，没意义。
 */
public class ChainedQueryTransformer implements QueryTransformer {

    private final List<QueryTransformer> transformers;

    public ChainedQueryTransformer(List<QueryTransformer> transformers) {
        if (transformers == null || transformers.isEmpty()) {
            throw new IllegalArgumentException("transformers must be non-empty");
        }
        this.transformers = List.copyOf(transformers);
    }

    @Override
    public Collection<Query> transform(Query query) {
        Collection<Query> current = List.of(query);
        for (QueryTransformer t : transformers) {
            List<Query> next = new ArrayList<>();
            for (Query q : current) {
                next.addAll(t.transform(q));
            }
            current = next;
        }
        return current;
    }
}
