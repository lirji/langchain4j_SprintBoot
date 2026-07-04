package com.lrj.langchain4j.eval.retrieval;

import java.util.List;

/**
 * 一条检索黄金集 case：一个 query + 它<strong>应当</strong>被召回的相关文档 id 列表。
 *
 * <p>{@code relevantDocIds} 是人工标注的 ground truth（"这个问题的答案在这些文档里"）。id 粒度见
 * {@link RetrievalMetrics} 注释：文件级（{@code project-faq.md}，对 chunk 切分漂移鲁棒，推荐）或
 * 精确级（{@code project-faq.md#2}，钉死具体 section）。跟 {@link com.lrj.langchain4j.eval.EvalCase}
 * 的区别是：那个走 LLM 判生成质量，这个不经 LLM、只量检索器把相关文档捞回来了没。
 */
public record RetrievalCase(
        String id,
        String question,
        List<String> relevantDocIds
) {}
