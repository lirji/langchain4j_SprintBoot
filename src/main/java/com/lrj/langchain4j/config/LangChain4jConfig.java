package com.lrj.langchain4j.config;

import com.lrj.langchain4j.rag.CategoryContext;
import com.lrj.langchain4j.rag.hybrid.DocumentMirror;
import com.lrj.langchain4j.security.TenantContext;
import com.lrj.langchain4j.rag.hybrid.HanLpKeywordTokenizer;
import com.lrj.langchain4j.rag.hybrid.KeywordContentRetriever;
import com.lrj.langchain4j.rag.hybrid.KeywordTokenizer;
import com.lrj.langchain4j.rag.TaggedSourceContentInjector;
import com.lrj.langchain4j.rag.hybrid.SimpleKeywordTokenizer;
import com.lrj.langchain4j.rag.scoring.OllamaLlmScoringModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.jina.JinaScoringModel;
import dev.langchain4j.model.scoring.ScoringModel;
import com.lrj.langchain4j.rag.ChainedQueryTransformer;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;

import java.util.ArrayList;
import java.util.List;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Configuration
public class LangChain4jConfig {

    /**
     * Retriever used when reranking is disabled — returns final top-k directly.
     */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("vectorRetriever")
    @ConditionalOnProperty(name = "app.rag.rerank.enabled", havingValue = "false", matchIfMissing = true)
    public ContentRetriever directContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                                   EmbeddingModel embeddingModel,
                                                   @Value("${app.rag.top-k:5}") int topK,
                                                   @Value("${app.rag.min-score:0.3}") double minScore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(topK)
                .minScore(minScore)
                .dynamicFilter(query -> tenantScopedFilter(CategoryContext.get()))
                .build();
    }

    /**
     * Retriever used when reranking is enabled — pulls a larger candidate set
     * for the scoring model to re-rank down to top-k.
     */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("vectorRetriever")
    @ConditionalOnProperty(name = "app.rag.rerank.enabled", havingValue = "true")
    public ContentRetriever candidateContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                                      EmbeddingModel embeddingModel,
                                                      @Value("${app.rag.rerank.candidate-size:20}") int candidateSize,
                                                      @Value("${app.rag.min-score:0.3}") double minScore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(candidateSize)
                .minScore(minScore)
                .dynamicFilter(query -> tenantScopedFilter(CategoryContext.get()))
                .build();
    }

    /**
     * 检索时强制 AND 一个 {@code tenantId} filter —— 多租户隔离的兜底。category 仍按
     * {@link CategoryContext} 可选叠加。
     *
     * <p>{@code TenantContext.current()} 在未挂 auth filter 时返回 {@link TenantContext#ANONYMOUS}，
     * 即"未鉴权也能查到 anonymous 租户的数据"。生产部署确保 {@code app.security.enabled=true}
     * 即可保证只有合法 key 能匹配到任何片段。
     */
    private static Filter tenantScopedFilter(String category) {
        Filter tenant = metadataKey("tenantId").isEqualTo(TenantContext.current().tenantId());
        if (category == null) return tenant;
        return Filter.and(tenant, metadataKey("category").isEqualTo(category));
    }

    @Bean
    @ConditionalOnExpression("'${app.rag.rerank.enabled:false}' == 'true' and '${app.rag.rerank.type:llm}' == 'llm'")
    public ScoringModel ollamaLlmScoringModel(ChatModel chatModel) {
        return new OllamaLlmScoringModel(chatModel);
    }

    @Bean
    @ConditionalOnExpression("'${app.rag.rerank.enabled:false}' == 'true' and '${app.rag.rerank.type:llm}' == 'jina'")
    public ScoringModel jinaScoringModel(@Value("${app.rag.rerank.jina.api-key}") String apiKey,
                                         @Value("${app.rag.rerank.jina.model-name}") String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("app.rag.rerank.jina.api-key is required when type=jina (set JINA_API_KEY env var)");
        }
        return JinaScoringModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.hybrid.tokenizer", havingValue = "hanlp")
    public KeywordTokenizer hanLpKeywordTokenizer() {
        return new HanLpKeywordTokenizer();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.hybrid.tokenizer", havingValue = "simple", matchIfMissing = true)
    public KeywordTokenizer simpleKeywordTokenizer() {
        return new SimpleKeywordTokenizer();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.hybrid.enabled", havingValue = "true")
    public KeywordContentRetriever keywordContentRetriever(DocumentMirror mirror,
                                                           KeywordTokenizer tokenizer,
                                                           @Value("${app.rag.top-k:5}") int topK,
                                                           @Value("${app.rag.rerank.enabled:false}") boolean rerank,
                                                           @Value("${app.rag.rerank.candidate-size:20}") int candidateSize) {
        return new KeywordContentRetriever(mirror, tokenizer, rerank ? candidateSize : topK);
    }

    /**
     * <strong>始终构造</strong>的 RetrievalAugmentor —— 即使 rerank / hybrid 都关闭，
     * 也通过这个 augmentor 跑，目的是无条件挂上自定义的 {@link TaggedSourceContentInjector}：
     * 内置 {@code DefaultContentInjector} 只把片段用换行拼起来，模型看不到来源 id，
     * 没法按 {@code [doc=文件名#片段号]} 引用 —— 这是配合 {@code AssistantProperties.citationPolicy}
     * 闭环的关键一环。
     *
     * <p>rerank / hybrid 仍然条件化在 router / aggregator 内部组合，效果不变。
     * {@code @AiService} 自动发现见到 RetrievalAugmentor 后就不再单独装 ContentRetriever Bean，
     * 所以 directContentRetriever / candidateContentRetriever 现在是这个 augmentor 的依赖而非直接被 AiService 用。
     */
    /**
     * Query expansion：开了之后用 LLM 把 1 个 query 扩成 N 个变体（默 3），多路并行召回，
     * `DefaultContentAggregator` 用 RRF 融合。代价是每条 query 多 1 次 LLM call 做扩展。
     *
     * <p>跟 rerank 互补：expansion 提升**召回**（让相关 chunk 更可能被检索到），
     * rerank 提升**精度**（已召回的候选里挑最相关）。生产场景两个都开效果叠加。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.query-expansion.enabled", havingValue = "true")
    public QueryTransformer expandingQueryTransformer(ChatModel chatModel,
                                                      @Value("${app.rag.query-expansion.n:3}") int n) {
        return ExpandingQueryTransformer.builder()
                .chatModel(chatModel)
                .n(n)
                .build();
    }

    /**
     * History-aware retrieval：把多轮对话 history 压成 self-contained query。
     * 典型场景：用户问"什么是 Spring DI" → 然后问"它跟 IoC 啥区别"，没这个 transformer 会去
     * 检索"它跟 IoC 啥区别"召回不到，有了就改写成"Spring DI 跟 Spring IoC 啥区别"再检索。
     *
     * <p>跟 {@link #expandingQueryTransformer} 互补，可同时开 —— 见
     * {@link #retrievalAugmentor} 里的 chain 组装顺序（compress 先，expand 后）。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.history-aware.enabled", havingValue = "true")
    public QueryTransformer compressingQueryTransformer(ChatModel chatModel) {
        return CompressingQueryTransformer.builder()
                .chatModel(chatModel)
                .build();
    }

    @Bean
    public RetrievalAugmentor retrievalAugmentor(@org.springframework.beans.factory.annotation.Qualifier("vectorRetriever")
                                                 ContentRetriever vectorRetriever,
                                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                 KeywordContentRetriever keywordRetriever,
                                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                 com.lrj.langchain4j.rag.graph.GraphRetrieverHolder graphHolder,
                                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                 ScoringModel scoringModel,
                                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                 @org.springframework.beans.factory.annotation.Qualifier("compressingQueryTransformer")
                                                 QueryTransformer compressing,
                                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                 @org.springframework.beans.factory.annotation.Qualifier("expandingQueryTransformer")
                                                 QueryTransformer expanding,
                                                 @Value("${app.rag.top-k:5}") int topK,
                                                 @Value("${app.rag.rerank.min-score:0.0}") double rerankMinScore) {
        // 多路并联：vector 恒在 + keyword（hybrid 开时）+ graph（GraphRAG 开时）。
        // 三路召回都扇出，由下面的 aggregator 用 RRF 融合。graph 路在 app.rag.graph.enabled=false
        // 时 Bean 不存在 → 此处 null → 不进 router，检索链与历史完全一致。
        List<ContentRetriever> retrievers = new ArrayList<>();
        retrievers.add(vectorRetriever);
        if (keywordRetriever != null) retrievers.add(keywordRetriever);
        if (graphHolder != null) retrievers.add(graphHolder.retriever());
        QueryRouter router = new DefaultQueryRouter(retrievers);

        // rerank 后再卡一道分数门槛：0.0（默认）= 不丢，保留 reranker 排序后的全部 top-k；
        // 调高（如 0.5）= 重排打分低于阈值的候选直接剔除，避免无关 chunk 占满 top-k。
        // 跟召回侧 app.rag.min-score 正交：那个治"召回什么"，这个治"重排后留什么"。
        ContentAggregator aggregator = (scoringModel != null)
                ? ReRankingContentAggregator.builder()
                        .scoringModel(scoringModel)
                        .maxResults(topK)
                        .minScore(rerankMinScore)
                        .build()
                : new DefaultContentAggregator();

        // 链组合顺序敏感：compress 必须在 expand 前 ——
        // 不然 expander 看到带代词的 query 扩出 N 个一样有歧义的变体。
        // 0/1 个 transformer 时不用包 chain，直接用单个 transformer。
        QueryTransformer composed = composeTransformers(compressing, expanding);

        DefaultRetrievalAugmentor.DefaultRetrievalAugmentorBuilder b = DefaultRetrievalAugmentor.builder()
                .queryRouter(router)
                .contentAggregator(aggregator)
                .contentInjector(new TaggedSourceContentInjector());
        if (composed != null) {
            b.queryTransformer(composed);
        }
        return b.build();
    }

    private static QueryTransformer composeTransformers(QueryTransformer compressing, QueryTransformer expanding) {
        List<QueryTransformer> chain = new ArrayList<>(2);
        if (compressing != null) chain.add(compressing);
        if (expanding != null) chain.add(expanding);
        if (chain.isEmpty()) return null;
        if (chain.size() == 1) return chain.get(0);
        return new ChainedQueryTransformer(chain);
    }
}
