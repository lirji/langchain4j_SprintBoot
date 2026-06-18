package com.lrj.langchain4j.config;

import com.lrj.langchain4j.rag.graph.EntityLinker;
import com.lrj.langchain4j.rag.graph.GraphContentRetriever;
import com.lrj.langchain4j.rag.graph.GraphExtractor;
import com.lrj.langchain4j.rag.graph.GraphIngestor;
import com.lrj.langchain4j.rag.graph.GraphRetrieverHolder;
import com.lrj.langchain4j.rag.graph.GraphStore;
import com.lrj.langchain4j.rag.graph.InMemoryGraphStore;
import com.lrj.langchain4j.rag.graph.JdbcGraphStore;
import com.lrj.langchain4j.rag.graph.LlmEntityLinker;
import com.lrj.langchain4j.rag.graph.QueryEntityExtractor;
import com.lrj.langchain4j.rag.graph.TokenEntityLinker;
import com.lrj.langchain4j.rag.hybrid.KeywordTokenizer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * GraphRAG 装配。<strong>整个 config 条件化在 {@code app.rag.graph.enabled=true}</strong> ——
 * 关闭（默认）时下列 Bean 全不存在：检索链零变化、入库钩子零开销。
 *
 * <p>抽取器 / llm 实体链接器都走 temp=0 判官模型（{@link LlmConfig#buildJudgeChatModel}），
 * <strong>不注册 ChatModel Bean</strong>，跟 {@code Critic}/{@code Judge} 同思路避免多 ChatModel 冲突。
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.graph.enabled", havingValue = "true")
public class GraphRagConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.rag.graph")
    public GraphRagProperties graphRagProperties() {
        return new GraphRagProperties();
    }

    // -------- store: in-memory（默认）/ jdbc（MySQL 持久化） --------

    @Bean
    @ConditionalOnProperty(name = "app.rag.graph.store", havingValue = "in-memory", matchIfMissing = true)
    public GraphStore inMemoryGraphStore() {
        return new InMemoryGraphStore();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.graph.store", havingValue = "jdbc")
    public GraphStore jdbcGraphStore(GraphRagProperties props) {
        GraphRagProperties.Jdbc j = props.getJdbc();
        return new JdbcGraphStore(j.getUrl(), j.getUsername(), j.getPassword(), j.getTable(), j.isCreateTable());
    }

    // -------- 抽取器 --------

    @Bean
    public GraphExtractor graphExtractor(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        ChatModel extractModel = llmConfig.buildJudgeChatModel(props);   // temp=0，确定性抽取
        return AiServices.builder(GraphExtractor.class)
                .chatModel(extractModel)
                .build();
    }

    // -------- 实体链接：token（默认）/ llm --------

    @Bean
    @ConditionalOnProperty(name = "app.rag.graph.entity-linking", havingValue = "token", matchIfMissing = true)
    public EntityLinker tokenEntityLinker(GraphStore graphStore, KeywordTokenizer tokenizer) {
        return new TokenEntityLinker(graphStore, tokenizer);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.graph.entity-linking", havingValue = "llm")
    public EntityLinker llmEntityLinker(GraphStore graphStore, LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        QueryEntityExtractor queryExtractor = AiServices.builder(QueryEntityExtractor.class)
                .chatModel(llmConfig.buildJudgeChatModel(props))
                .build();
        return new LlmEntityLinker(queryExtractor, graphStore);
    }

    // -------- 检索路 --------

    /**
     * 用 {@link GraphRetrieverHolder} 包装、<strong>不直接注册成 ContentRetriever bean</strong> ——
     * 否则会和 {@code directContentRetriever} 一起被 LangChain4j {@code @AiService} 的 ContentRetriever
     * 枚举命中、抛 Conflict（见 GraphRetrieverHolder 注释）。{@code retrievalAugmentor} 取出加进 router。
     */
    @Bean
    public GraphRetrieverHolder graphRetrieverHolder(GraphStore graphStore,
                                                     EntityLinker entityLinker,
                                                     GraphRagProperties props) {
        return new GraphRetrieverHolder(
                new GraphContentRetriever(graphStore, entityLinker, props.getMaxHops(), props.getMaxTriples()));
    }

    // -------- 后台建图线程池 --------

    @Bean(name = "graphExecutor")
    public Executor graphExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);          // 建图是 IO/LLM-bound 的后台批活，1-2 根够；不抢主请求资源
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(64);
        ex.setThreadNamePrefix("graph-ingest-");
        ex.initialize();
        return ex;
    }

    // -------- 入库钩子 --------

    @Bean
    public GraphIngestor graphIngestor(GraphExtractor graphExtractor,
                                       GraphStore graphStore,
                                       GraphRagProperties props,
                                       @Qualifier("graphExecutor") Executor graphExecutor) {
        Set<String> whitelist = new HashSet<>(props.getExtract().getRelationTypes());
        return new GraphIngestor(graphExtractor, graphStore,
                props.getExtract().getMaxTriplesPerChunk(),
                graphExecutor, props.isAsync(),
                whitelist, props.getAliases());
    }
}
