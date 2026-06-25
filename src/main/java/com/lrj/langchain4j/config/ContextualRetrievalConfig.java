package com.lrj.langchain4j.config;

import com.lrj.langchain4j.rag.contextual.ChunkContextualizer;
import com.lrj.langchain4j.rag.contextual.ContextualEnricher;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contextual Retrieval（Anthropic）装配：仅 {@code app.rag.contextual.enabled=true} 时构造。
 *
 * <p>{@link ChunkContextualizer} 走独立 temp=0 ChatModel（{@link LlmConfig#buildJudgeChatModel}），
 * <strong>不注册 ChatModel Bean</strong>（同 {@code GroundednessChecker} / {@code GraphExtractor}）。
 * {@link ContextualEnricher} 由 {@code RagIngestionService} / {@code DocumentService} 经
 * {@code ObjectProvider} 软依赖调用——关闭时 Bean 不存在、入库链零回归。
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.contextual.enabled", havingValue = "true")
public class ContextualRetrievalConfig {

    @Bean
    public ContextualEnricher contextualEnricher(
            LlmConfig llmConfig,
            LlmConfig.LlmProperties props,
            // 喂给上下文生成器的文档原文截断上限（控成本/上下文）；单位字符
            @Value("${app.rag.contextual.max-doc-chars:8000}") int maxDocChars,
            // 文档切出的 chunk 数 < 该值时跳过改写（单 chunk 即全文、无指代歧义）
            @Value("${app.rag.contextual.min-segments:2}") int minSegments) {
        ChatModel coldModel = llmConfig.buildJudgeChatModel(props);   // temp=0，situating 是确定性任务
        ChunkContextualizer contextualizer = AiServices.builder(ChunkContextualizer.class)
                .chatModel(coldModel)
                .build();
        return new ContextualEnricher(contextualizer, maxDocChars, minSegments);
    }
}
