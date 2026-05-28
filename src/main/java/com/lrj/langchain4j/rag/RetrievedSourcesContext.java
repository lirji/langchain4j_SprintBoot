package com.lrj.langchain4j.rag;

import java.util.List;

/**
 * Per-request 持有本轮 RAG 实际检索并注入到 prompt 的 source 列表。
 *
 * <p>{@link TaggedSourceContentInjector} 在 {@code inject()} 时写入；grounding 后校验
 * （{@code GroundingService}）在 {@code Assistant.chat} 返回后读取，用来：
 * <ul>
 *   <li>Layer 0：核对答案里 {@code [doc=ID]} 引用的 id 是否真在检索集合里（确定性，零 LLM）</li>
 *   <li>Layer 1：把 source 原文喂给 {@code GroundednessChecker} 做 faithfulness 判定</li>
 * </ul>
 *
 * <p>injector 与同步 {@code chat(...)} 调用在同一线程（{@code DefaultRetrievalAugmentor.augment}
 * 的 inject 在调用线程同步执行），所以 ThreadLocal 能正确传递。调用方负责 try/finally clear，
 * 防止 worker 线程复用时串数据。
 */
public final class RetrievedSourcesContext {

    public record Source(String id, String text) {}

    private static final ThreadLocal<List<Source>> CURRENT = new ThreadLocal<>();

    private RetrievedSourcesContext() {}

    public static void set(List<Source> sources) {
        CURRENT.set(sources);
    }

    public static List<Source> get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
