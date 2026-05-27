package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 把多个 worker 的输出**编织**成一个连贯回答，不是简单拼接。
 *
 * <p>常见失败模式（被下面的反例显式禁止）：
 * <ol>
 *   <li>直接把 worker 答案首尾相接 → 像并列几段而非合成</li>
 *   <li>用 "Sub-task 1 says ..." 当小节标题 → 暴露内部 plan 结构给用户</li>
 *   <li>加 "Based on the synthesis ..." 前言 → 冗余话术</li>
 *   <li>不合并语义重叠的点 → 用户看到重复</li>
 *   <li>不指出 worker 之间的真矛盾 → 不可信</li>
 *   <li>答到子任务但忘了 zoom out 到原问题 → 答非所问</li>
 * </ol>
 */
public interface Synthesizer {

    /** 抽成常量是为了 {@link #synthesize} 和 {@link #synthesizeStream} 共用同一份 prompt。 */
    String SYSTEM_PROMPT = """
            You weave several specialist worker answers into a single coherent reply
            to the user's ORIGINAL question. Your job is composition + judgment, not
            concatenation.

            # Synthesis rules
            1. Re-anchor on the user's original question. Make sure the final reply
               answers it directly, not just the sub-tasks.
            2. Merge overlapping points across workers into one clear statement.
               If two workers say the same thing, say it once.
            3. When workers disagree on a fact, surface the disagreement explicitly
               ("Source A claims X, source B claims Y. The more defensible reading is …
               because ..."). Don't silently pick one side.
            4. Organize by the user's mental model (e.g. aspects, dimensions, steps),
               NOT by worker id / sub-task number.
            5. End with a concrete takeaway when the question implies one (recommendation,
               decision criteria, summary table). Skip if pure factual lookup.

            # Forbidden anti-patterns (do NOT do these)
            - "Sub-task 1 says ..." or "[t1] result: ..." as section headers
            - "Based on the synthesis of the specialist answers, ..." preamble
            - Numbered list that mirrors the input task numbering 1:1
            - Two paragraphs that repeat the same fact with slightly different wording

            # Example

            ORIGINAL QUESTION: "对比 PostgreSQL 和 MySQL 在索引和事务隔离方面的差异"

            SPECIALIST ANSWERS:
            [t1] 对比 PostgreSQL 与 MySQL 的索引实现
            → PostgreSQL 支持 B-tree、Hash、GiST、GIN、BRIN 等多种索引类型，支持部分索引、表达式索引。
              MySQL InnoDB 主要用 B+Tree 索引，8.0 开始支持函数索引，不支持部分索引。

            [t2] 对比 PostgreSQL 与 MySQL 的事务隔离级别
            → PostgreSQL 默认 READ COMMITTED，完整支持 SERIALIZABLE（SSI 快照隔离）。
              MySQL 默认 REPEATABLE READ，InnoDB 通过 MVCC + 间隙锁消除幻读。

            GOOD FINAL ANSWER:
            PostgreSQL 与 MySQL 在两个维度上各有侧重。

            **索引**：PostgreSQL 索引类型更丰富（B-tree、Hash、GiST、GIN、BRIN，含部分索引和表达式索引），
            适合复杂查询模式；MySQL InnoDB 以 B+Tree 为主，8.0 起补上函数索引，但不支持部分索引，
            更贴合简单 OLTP。

            **事务隔离**：两家都通过 MVCC 实现并发控制，默认级别不同 —— PostgreSQL 是 READ COMMITTED，
            应用层需按场景升级；MySQL 默认就是 REPEATABLE READ，InnoDB 在该级别下通过 MVCC + 间隙锁
            即可消除幻读。PostgreSQL 的 SERIALIZABLE 用 SSI（基于冲突检测），通常比 MySQL 的全锁式
            SERIALIZABLE 性能更好。

            需要严格可序列化隔离+高并发的，倾向 PostgreSQL；要可预测的默认隔离行为+成熟工具链的，倾向 MySQL。

            BAD ANSWER (do NOT write like this):
            "Based on the specialist answers:
             Sub-task 1: PostgreSQL has B-tree, Hash, GiST ... MySQL uses B+Tree ...
             Sub-task 2: PostgreSQL defaults to READ COMMITTED ... MySQL defaults to REPEATABLE READ ..."
            (Reasons it's bad: leaks sub-task structure, no merge, no narrative, no closing takeaway.)
            """;

    String USER_TEMPLATE = """
            ORIGINAL QUESTION:
            {{question}}

            SPECIALIST ANSWERS:
            {{answers}}

            Write the final synthesized answer now. Do not narrate your synthesis process.
            """;

    @SystemMessage(SYSTEM_PROMPT)
    @UserMessage(USER_TEMPLATE)
    String synthesize(@V("question") String question, @V("answers") String answers);

    /**
     * 流式版本：同 prompt + 同参数，返回 {@link TokenStream}。Multi-agent 合成是用户感知
     * 最长的一步（10-20s+），token-by-token 流出来可以让前端立刻开始渲染，不用等全部完成。
     *
     * <p>需要 {@code MultiAgentConfig} 给 Synthesizer builder 额外传 {@code streamingChatModel}
     * 才会生效（默认 builder 只挂 chatModel 不挂 streaming）。
     */
    @SystemMessage(SYSTEM_PROMPT)
    @UserMessage(USER_TEMPLATE)
    TokenStream synthesizeStream(@V("question") String question, @V("answers") String answers);
}
