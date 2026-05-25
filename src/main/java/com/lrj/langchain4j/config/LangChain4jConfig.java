package com.lrj.langchain4j.config;

import com.lrj.langchain4j.rag.CategoryContext;
import com.lrj.langchain4j.rag.hybrid.DocumentMirror;
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
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
                .dynamicFilter(query -> {
                    String category = CategoryContext.get();
                    return category == null ? null : metadataKey("category").isEqualTo(category);
                })
                .build();
    }

    /**
     * Retriever used when reranking is enabled — pulls a larger candidate set
     * for the scoring model to re-rank down to top-k.
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.rerank.enabled", havingValue = "true")
    public ContentRetriever candidateContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                                      EmbeddingModel embeddingModel,
                                                      @Value("${app.rag.rerank.candidate-size:20}") int candidateSize) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(candidateSize)
                .minScore(0.3)
                .dynamicFilter(query -> {
                    String category = CategoryContext.get();
                    return category == null ? null : metadataKey("category").isEqualTo(category);
                })
                .build();
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
    @Bean
    public RetrievalAugmentor retrievalAugmentor(ContentRetriever vectorRetriever,
                                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                 KeywordContentRetriever keywordRetriever,
                                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                 ScoringModel scoringModel,
                                                 @Value("${app.rag.top-k:5}") int topK) {
        QueryRouter router = (keywordRetriever != null)
                ? new DefaultQueryRouter(vectorRetriever, keywordRetriever)
                : new DefaultQueryRouter(vectorRetriever);

        ContentAggregator aggregator = (scoringModel != null)
                ? ReRankingContentAggregator.builder()
                        .scoringModel(scoringModel)
                        .maxResults(topK)
                        .minScore(0.0)
                        .build()
                : new DefaultContentAggregator();

        return DefaultRetrievalAugmentor.builder()
                .queryRouter(router)
                .contentAggregator(aggregator)
                .contentInjector(new TaggedSourceContentInjector())
                .build();
    }
}
