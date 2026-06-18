package com.lrj.langchain4j.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一条 golden case。
 *
 * <p>{@code type}（默认 {@code "chat"}）决定 {@link EvaluationRunner} dispatch 到哪个 endpoint：
 * <ul>
 *   <li>{@code chat} — 走 {@code Assistant.chat(...)}（默认）</li>
 *   <li>{@code graph} — 同 chat dispatch，但用独立 set 隔离 GraphRAG 前置（建图 + 多跳 case）。
 *       需 {@code app.rag.graph.enabled=true}（第三路 retriever 才并入 router）+ {@code app.eval.auto-ingest=true}（建图）</li>
 *   <li>{@code grounded} — 同 chat，但包一层 {@code GroundingService}（跟 controller {@code /chat} 一致），
 *       用来测 RAG 事实幻觉的事后校验闸门。需 {@code app.rag.grounding.enabled=true} 闸门才运行、
 *       {@code app.eval.auto-ingest=true} 才有 source 可校验</li>
 *   <li>{@code extract} — 走 {@code Extractor.extractTicket(...)}，question 当 input text，Ticket 序列化成 JSON 喂 Judge</li>
 *   <li>{@code multi-agent} — 走 {@code MultiAgentService.run(...)}，把 plan 任务数 + finalAnswer 一起拼成 string 评分</li>
 *   <li>{@code reflexive} — 走 {@code ReflexiveService.chatReflexive(...)}，取 {@code finalAnswer} 评分</li>
 *   <li>{@code sql} — 走 {@code NlToSqlService.ask(...)}，输出 guardBlocked + SQL + 行数 + 解读；需 {@code app.nl2sql.enabled}</li>
 *   <li>{@code a2a} — 走 {@code A2aService.dispatch("message/send", ...)}（chat skill 同步），输出 JSON-RPC response；需 {@code app.a2a.enabled}</li>
 *   <li>{@code workflow} — 走 {@code WorkflowService.start(...)}，输出 status + priority + reply；需 {@code app.workflow.enabled} + MySQL</li>
 * </ul>
 *
 * <p>{@code judgeHint} 用法见 {@link Judge} 注释——只补 Judge 看 (question, answer) 无法推断的领域信息，
 * 不要直接喂答案。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvalCase(
        String id,
        String question,
        List<String> mustInclude,
        List<String> mustNotInclude,
        String judgeHint,
        /** "chat" | "graph" | "grounded" | "extract" | "multi-agent" | "reflexive" | "sql" | "a2a" | "workflow"；null/blank 视为 "chat"。 */
        String type
) {
    public EvalCase(String id, String question, List<String> mustInclude, List<String> mustNotInclude) {
        this(id, question, mustInclude, mustNotInclude, null, null);
    }

    public EvalCase(String id, String question, List<String> mustInclude, List<String> mustNotInclude, String judgeHint) {
        this(id, question, mustInclude, mustNotInclude, judgeHint, null);
    }

    public String effectiveType() {
        return (type == null || type.isBlank()) ? "chat" : type;
    }
}
