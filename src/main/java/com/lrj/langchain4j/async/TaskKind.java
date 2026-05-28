package com.lrj.langchain4j.async;

/**
 * 任务类型。目前只接 {@code MULTI_AGENT}（multi-agent 同步调用 10–20s，前端 timeout 风险大，
 * 改异步最先受益）。未来可以接 reflexive / RAG 大文件批量入库等长任务。
 */
public enum TaskKind {
    MULTI_AGENT
}
