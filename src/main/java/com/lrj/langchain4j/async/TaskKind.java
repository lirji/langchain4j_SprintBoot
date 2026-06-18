package com.lrj.langchain4j.async;

/**
 * 任务类型。
 * <ul>
 *   <li>{@code MULTI_AGENT} —— multi-agent 同步调用 10–20s，前端 timeout 风险大，改异步最先受益。</li>
 *   <li>{@code DEEP_AGENT} —— 深度 Agent 长程循环（多步 LLM 调用），同步端点易超时，投后台异步。</li>
 * </ul>
 * 未来可以接 reflexive / RAG 大文件批量入库等长任务。
 */
public enum TaskKind {
    MULTI_AGENT,
    DEEP_AGENT
}
