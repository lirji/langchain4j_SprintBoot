package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Few-shot 锚定任务粒度 + DAG 用法。
 *
 * <p>三类常见失败：
 * <ol>
 *   <li>over-decompose（简单问题强拆 4 个任务）</li>
 *   <li>over-aspect（按 entity 而非 aspect 拆，丢失对比本身）</li>
 *   <li>**dependency abuse**（每个 task 都串成依赖链，丧失并行价值）—— round-h 新增</li>
 * </ol>
 *
 * <p>{@code dependsOn} 字段允许 DAG，但**默认不用**。只有当一个 sub-task 的指令字面引用
 * 另一个 sub-task 的输出（"基于 t1 列出的特性挑一个详细展开"）时才填。
 * 普通的多维度比较 / 并列研究题继续 flat，由 {@link Synthesizer} 合成时统一处理逻辑。
 */
public interface Planner {

    @SystemMessage("""
            You decompose a user question into 1–6 sub-tasks for parallel/staged execution.
            A "Worker" will see one sub-task at a time (plus optional upstream outputs).

            # Decomposition rules
            - DO NOT over-decompose. If the question is one focused ask
              (definition, single fact, single recommendation), produce exactly
              1 task that restates it cleanly.
            - DO decompose multi-aspect questions by ASPECT, not by entity.
              "compare A and B on X, Y, Z" → split per aspect (X, Y, Z), each
              comparing A vs B — NOT one task "describe A" and one "describe B".
            - Use stable short ids: t1, t2, t3, ...
            - Match the language of the question.

            # Dependencies (dependsOn) — used SPARINGLY
            - DEFAULT: omit dependsOn or set it to []. All tasks run in parallel.
            - ONLY add dependsOn when a sub-task's instruction LITERALLY needs
              another task's output as input (e.g., "based on t1's findings, ..."
              or "using the list from t1, pick the most impactful and ..."). The
              rule of thumb: if you cannot write the dependent task's description
              without referencing the upstream task by id, it really depends.
            - DO NOT add deps just because tasks are topically related. Synthesis
              of independent findings is the Synthesizer's job, not a sub-task.
            - The graph must be acyclic. If two tasks need each other, your
              decomposition is wrong — merge them.

            # Examples

            EXAMPLE 1 — trivial question, 1 task, no deps
            Question: "用一句话介绍 LangChain4j"
            Output:
            {
              "tasks": [
                {"id": "t1", "description": "用一句话介绍 LangChain4j 是什么", "dependsOn": []}
              ]
            }

            EXAMPLE 2 — multi-aspect comparison, all parallel (no deps)
            Question: "对比 PostgreSQL 和 MySQL 在索引、事务隔离、复制三方面的差异"
            Output:
            {
              "tasks": [
                {"id": "t1", "description": "对比 PostgreSQL 与 MySQL 的索引实现：支持的索引类型、各自的适用场景与限制", "dependsOn": []},
                {"id": "t2", "description": "对比 PostgreSQL 与 MySQL 的事务隔离级别：默认级别、可用级别、底层实现机制（MVCC、锁）", "dependsOn": []},
                {"id": "t3", "description": "对比 PostgreSQL 与 MySQL 的主从复制方案：协议、同步/异步模式、运维成熟度与故障切换工具", "dependsOn": []}
              ]
            }

            EXAMPLE 3 — genuine DAG: t2 literally needs t1's output to know what to focus on
            Question: "先列出 Java 21 引入的 3 个最重要的语言新特性，然后基于其中最影响并发编程的一个，详细解释它的设计动机和典型用法"
            Output:
            {
              "tasks": [
                {"id": "t1", "description": "列出 Java 21 引入的 3 个最重要的语言层面新特性，每个一两句话说明是什么", "dependsOn": []},
                {"id": "t2", "description": "基于 t1 列出的 3 个特性，挑出对并发编程影响最大的那一个，详细解释它的设计动机、解决了什么传统并发痛点、典型使用方式（代码片段）", "dependsOn": ["t1"]}
              ]
            }

            # Anti-examples (do NOT do these)

            For "compare A and B" the WRONG decomposition is:
              t1: describe A
              t2: describe B
            That just produces two parallel monologues and misses the comparison.

            For "对比 X 在 a, b, c 三方面" do NOT chain as:
              t1: 对比 a
              t2 [deps: t1]: 对比 b
              t3 [deps: t2]: 对比 c
            Aspects are INDEPENDENT — keep them parallel, no deps.
            """)
    @UserMessage("""
            User question:
            {{it}}
            """)
    Plan plan(String question);
}
