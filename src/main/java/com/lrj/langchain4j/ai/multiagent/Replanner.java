package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Replan a multi-agent task after the first attempt's synthesized answer falls below the
 * quality threshold. Replanner sees the previous plan + previous final answer + the
 * critic's mainIssue, and is expected to produce a REVISED plan that addresses what went
 * wrong — NOT to rerun the same decomposition.
 *
 * <p>独立于 {@link Planner}：Planner 是从零拆任务，Replanner 是看着上一轮的反馈做结构性
 * 调整。两者的 system prompt 关注点完全不同——硬塞到 Planner 里会让首轮 prompt 也被
 * "previous attempt" 的分支污染。
 *
 * <p>典型修订形态（few-shot 已覆盖）：
 * <ol>
 *   <li>原 plan 粒度过粗，某个 aspect 实际包含 2 个未答的子问题 → 拆细</li>
 *   <li>原 plan 漏了 critique 指出的某个维度 → 补一个 task</li>
 *   <li>原 plan task 描述歧义导致 Worker 答偏 → 改写描述更具体</li>
 *   <li>原 plan 错误地用了 DAG 串行（dependency abuse）→ 改成并行</li>
 * </ol>
 *
 * <p>禁止：把上一轮的 plan 原样输出。Replanner 必须做出实质改动，否则会陷入死循环
 * 直到 max-replans 用完。
 */
public interface Replanner {

    @SystemMessage("""
            You are revising a multi-agent execution plan after the first attempt's
            synthesized answer was judged insufficient. You see:
              - the user's original question
              - the previous plan (as JSON)
              - the previous final answer that was produced
              - the reviewer's mainIssue (the single most impactful thing to fix)
              - per-dimension scores: correctness / completeness / clarity

            Your job: produce a REVISED plan that, when executed by parallel workers
            and synthesized, will address the mainIssue. Output the same Plan shape
            (1–6 sub-tasks with stable ids, optional dependsOn).

            # Revision rules
            - You MUST make a substantive structural change. Do NOT return a plan
              identical (or trivially renamed) to the previous one — that wastes a
              full execution round.
            - Diagnose first, then revise. Pick the form that fits the mainIssue:
              * "missed aspect X" → add a sub-task for X
              * "answer was vague on Y" → rewrite that sub-task's description to
                demand specific, concrete output (numbers, code, named tradeoffs)
              * "two sub-tasks produced overlapping content" → merge them
              * "answer was factually wrong on Z" → rewrite that sub-task to
                request verification or a different angle
              * "tasks ran serial unnecessarily" → drop the dependsOn, run parallel
            - Keep stable ids where the sub-task is genuinely the same; use new
              ids (t4, t5, …) for added tasks. Don't reshuffle ids cosmetically.
            - Match the language of the question. Do not narrate your revision
              process — just output the new plan.

            # Example

            ORIGINAL QUESTION: "对比 PostgreSQL 和 MySQL 在索引、事务隔离、复制三方面的差异"

            PREVIOUS PLAN:
            {
              "tasks": [
                {"id":"t1","description":"对比 PostgreSQL 与 MySQL 的索引","dependsOn":[]},
                {"id":"t2","description":"对比 PostgreSQL 与 MySQL 的事务隔离","dependsOn":[]}
              ]
            }

            PREVIOUS ANSWER: "PostgreSQL 和 MySQL 在索引和事务隔离上各有侧重..."(没有提复制)

            CRITIC: correctness=0.9 completeness=0.4 clarity=0.8
            mainIssue: "用户问了三方面（索引、事务隔离、复制），答案只覆盖了前两个，
            完全没有提复制方案对比。"

            REVISED PLAN:
            {
              "tasks": [
                {"id":"t1","description":"对比 PostgreSQL 与 MySQL 的索引实现：支持的索引类型、各自的适用场景与限制","dependsOn":[]},
                {"id":"t2","description":"对比 PostgreSQL 与 MySQL 的事务隔离级别：默认级别、可用级别、底层实现机制","dependsOn":[]},
                {"id":"t3","description":"对比 PostgreSQL 与 MySQL 的主从复制方案：协议、同步/异步模式、运维成熟度与故障切换工具","dependsOn":[]}
              ]
            }

            (Why this is a good revision: it adds the missing aspect as a new
            independent task t3, keeps t1/t2 ids stable, and tightens their
            descriptions so workers produce more concrete output the second time.)
            """)
    @UserMessage("""
            ORIGINAL QUESTION:
            {{question}}

            PREVIOUS PLAN (JSON):
            {{previousPlan}}

            PREVIOUS FINAL ANSWER:
            {{previousAnswer}}

            CRITIC SCORES: correctness={{correctness}} completeness={{completeness}} clarity={{clarity}}
            mainIssue: {{mainIssue}}

            Produce the revised plan now.
            """)
    Plan revise(@V("question") String question,
                @V("previousPlan") String previousPlan,
                @V("previousAnswer") String previousAnswer,
                @V("correctness") double correctness,
                @V("completeness") double completeness,
                @V("clarity") double clarity,
                @V("mainIssue") String mainIssue);
}
