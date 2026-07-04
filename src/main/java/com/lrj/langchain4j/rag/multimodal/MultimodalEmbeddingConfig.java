package com.lrj.langchain4j.rag.multimodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 原生多模态 embedding 装配。<strong>整个 config 条件化在
 * {@code app.rag.multimodal-embedding.enabled=true}</strong>——关闭（默认）时相关 Bean 全不存在，
 * 零开销、零依赖网络，主 RAG 链完全不受影响。
 *
 * <p>刻意<strong>不注册 {@code EmbeddingModel} Bean</strong>：主 RAG 的文本 {@code EmbeddingModel}
 * 已由 {@code EmbeddingModelConfig} 装配，两者维度/语义空间不同，混在同一类型里会污染 RAG 自动装配
 * （与 {@code ai/vision} 不注册 ChatModel Bean 同思路）。这里只暴露自定义
 * {@link MultimodalEmbeddingModel} 接口。
 *
 * <p>{@link MultimodalRetrievalService} 复用主 {@link EmbeddingStore} Bean（始终存在），把 image
 * 向量与文本 chunk 存进同一物理库、靠 {@code type=image} metadata 区隔。
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.multimodal-embedding.enabled", havingValue = "true")
public class MultimodalEmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(MultimodalEmbeddingConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "app.rag.multimodal-embedding")
    public MultimodalEmbeddingProperties multimodalEmbeddingProperties() {
        return new MultimodalEmbeddingProperties();
    }

    @Bean
    public MultimodalEmbeddingModel multimodalEmbeddingModel(MultimodalEmbeddingProperties props,
                                                             ObjectMapper mapper) {
        log.info("Native multimodal embedding enabled: model={} dim={}",
                props.getModelName(), props.getDimension());
        return new DefaultMultimodalEmbeddingModel(props, mapper);
    }

    @Bean
    public MultimodalRetrievalService multimodalRetrievalService(MultimodalEmbeddingModel model,
                                                                 EmbeddingStore<TextSegment> store,
                                                                 MultimodalEmbeddingProperties props) {
        return new MultimodalRetrievalService(model, store, props.getTopK(), props.getMinScore());
    }
}
