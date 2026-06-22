package com.lrj.langchain4j.rag.graph;

/**
 * 把 {@link GraphContentRetriever} 包一层「非 ContentRetriever 类型」的 holder bean。
 *
 * <p><strong>为什么要这个 holder</strong>：LangChain4j 的 {@code @AiService} 自动装配会
 * {@code getBeanNamesForType(ContentRetriever.class)} 枚举所有 ContentRetriever bean，
 * 多于 1 个就直接抛 {@code Conflict: multiple beans ... ContentRetriever}（且 {@code @Primary}/
 * {@code @Qualifier} 都不顶用，跟多 ChatModel Bean 同一个坑）。把 graph 路直接注册成 ContentRetriever bean
 * 会和默认的 {@code directContentRetriever} 撞车。所以这里用 holder 把它藏起来——它本身不是
 * ContentRetriever，不被那次枚举命中；{@code LangChain4jConfig.retrievalAugmentor} 取出 {@link #retriever()}
 * 加进 router 即可。
 */
public record GraphRetrieverHolder(GraphContentRetriever retriever) {
}
