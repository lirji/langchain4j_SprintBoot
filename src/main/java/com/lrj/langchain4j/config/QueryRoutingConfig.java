package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.routing.BareAssistant;
import com.lrj.langchain4j.ai.routing.QueryClassifier;
import com.lrj.langchain4j.ai.tools.DateTimeTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Query routing 的 Bean 装配。{@code @ConditionalOnProperty(name="app.query-router.enabled")}
 * 默认关闭 —— 因为 classifier 多 1 次 LLM call，要等明确开启才值得。
 *
 * <ul>
 *   <li>{@link QueryClassifier} 用独立 ChatModel（temperature=0，同 Judge 思路保证分类稳定）</li>
 *   <li>{@link BareAssistant} 程序化构建，**不挂 RetrievalAugmentor**，跳过 RAG 开销；
 *       共享主 ChatModel + ChatMemoryProvider + Tools，保留会话连续性</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.query-router.enabled", havingValue = "true")
public class QueryRoutingConfig {

    /**
     * Classifier 走独立的 ChatModel 实例（temp=0），让同一 query 多次分类给出同一结果 ——
     * 否则 routing 会随机分流到不同后端，eval 没法比对。
     * 用法跟 {@link LlmConfig#buildJudgeChatModel} 一样：直接调 LlmConfig 的公开方法构造，
     * 不注册成额外 ChatModel Bean（避免和主 chatModel 类型冲突）。
     */
    @Bean
    public QueryClassifier queryClassifier(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        ChatModel classifierModel = llmConfig.buildJudgeChatModel(props);
        return AiServices.builder(QueryClassifier.class).chatModel(classifierModel).build();
    }

    /**
     * 不挂 RetrievalAugmentor 的 Assistant 变种。
     * 显式列出依赖：chatModel + chatMemoryProvider + tools。
     * 不传 augmentor / contentRetriever / contentInjector → 跳过 RAG。
     */
    @Bean
    public BareAssistant bareAssistant(ChatModel chatModel,
                                       ChatMemoryProvider memoryProvider,
                                       DateTimeTool dateTimeTool) {
        return AiServices.builder(BareAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .tools(dateTimeTool)
                .build();
    }
}
