package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Few-shot 锚定任务粒度：常见失败是 over-decompose（简单问题强拆 4 个任务）
 * 或 dependency-disguise（"先 A 再 B" 假装并行）。3 个例子 + 1 个反例覆盖典型场景。
 */
public interface Planner {

    @SystemMessage("""
            You decompose a user question into 1–6 INDEPENDENT sub-tasks that
            can be answered in parallel. A "Worker" will see ONLY one sub-task
            at a time, so each one must be a complete instruction.

            # Decomposition rules
            - DO NOT chain via dependencies ("after t1, then t2 ..."). If a
              task references another task's output, the plan is wrong.
            - DO NOT over-decompose. If the question is one focused ask
              (definition, single fact, single recommendation), produce
              exactly 1 task that restates it cleanly.
            - DO decompose multi-aspect questions by ASPECT, not by entity.
              "compare A and B on X, Y, Z" → split per aspect (X, Y, Z), each
              comparing A vs B — NOT one task "describe A" and one "describe B".
            - Use stable short ids: t1, t2, t3, ...
            - Match the language of the question.

            # Examples

            EXAMPLE 1 — trivial question, 1 task
            Question: "用一句话介绍 LangChain4j"
            Output:
            {
              "tasks": [
                {"id": "t1", "description": "用一句话介绍 LangChain4j 是什么"}
              ]
            }

            EXAMPLE 2 — multi-aspect comparison, split by aspect
            Question: "对比 PostgreSQL 和 MySQL 在索引、事务隔离、复制三方面的差异"
            Output:
            {
              "tasks": [
                {"id": "t1", "description": "对比 PostgreSQL 与 MySQL 的索引实现：支持的索引类型、各自的适用场景与限制"},
                {"id": "t2", "description": "对比 PostgreSQL 与 MySQL 的事务隔离级别：默认级别、可用级别、底层实现机制（MVCC、锁）"},
                {"id": "t3", "description": "对比 PostgreSQL 与 MySQL 的主从复制方案：协议、同步/异步模式、运维成熟度与故障切换工具"}
              ]
            }

            EXAMPLE 3 — research-style, split by sub-question
            Question: "我们考虑把生产数据库从 MySQL 迁到 PostgreSQL，要评估什么？"
            Output:
            {
              "tasks": [
                {"id": "t1", "description": "MySQL 迁移到 PostgreSQL 时常见的数据类型与字符集兼容性陷阱有哪些？"},
                {"id": "t2", "description": "MySQL 与 PostgreSQL 在 OLTP 写入吞吐与高并发场景下的性能差异表现如何？"},
                {"id": "t3", "description": "PostgreSQL 在生产环境的运维成熟度如何（监控、备份、HA 方案、社区工具链）？"},
                {"id": "t4", "description": "应用层从 MySQL 迁到 PostgreSQL 通常需要做哪些 SQL 语法或驱动改动？"},
                {"id": "t5", "description": "MySQL → PostgreSQL 迁移的常用工具与生产流量切换方案有哪些？"}
              ]
            }

            # Anti-example (do NOT do this)
            For "compare A and B" the WRONG decomposition is:
              t1: describe A
              t2: describe B
            That just produces two parallel monologues and misses the comparison
            itself. Decompose by ASPECT — e.g. "compare A vs B on aspect X".
            """)
    @UserMessage("""
            User question:
            {{it}}
            """)
    Plan plan(String question);
}
