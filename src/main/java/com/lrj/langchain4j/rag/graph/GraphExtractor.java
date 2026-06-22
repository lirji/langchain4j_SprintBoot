package com.lrj.langchain4j.rag.graph;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 从一段 chunk 文本里抽「实体–关系–实体」三元组。走 <strong>temp=0 判官模型</strong>
 * （{@link com.lrj.langchain4j.config.LlmConfig#buildJudgeChatModel}）——抽取是确定性任务，
 * 同一段文本每次该抽出同样的边，否则图会漂；跟 {@code Critic}/{@code Judge}/{@code SummarizingChatMemory}
 * 同思路，不注册 ChatModel Bean。
 *
 * <p>prompt 的三条硬约束（决定图质量上限）：① 只抽文中<strong>明确陈述</strong>的关系，禁止
 * 推断/补世界知识（防幻觉边）；② relation 用归一的动词短语，别每条都造新词；③ 实体用文中表面形式，
 * 消歧留给后续阶段（v1 不做）。few-shot 含一个<strong>反例</strong>锚定「别把修饰语连成边」。
 */
public interface GraphExtractor {

    @SystemMessage("""
            You extract a knowledge graph from a chunk of text, as (subject, relation, object) triples.

            STRICT RULES — violating these poisons the graph:
            1. Extract ONLY relationships explicitly stated in the text. Never infer, never add
               world knowledge, never connect entities that the text does not relate.
            2. Use a NORMALIZED verb phrase for `relation` (e.g. 隶属于 / 负责 / 依赖 / 位于 /
               属于 / 管理). Do not invent a fresh relation wording for every sentence.
            3. Use the entity's SURFACE FORM as it appears in the text for subject/object. Do not
               canonicalize or merge aliases — that is handled later.
            4. Skip vague/modifier "relations" (adjectives, sentiments). A triple must be a real,
               checkable fact between two named entities.
            5. If the text states no clear entity relationship, return an empty list. Do not pad.

            # Example 1 — organizational text
            TEXT: 张三是李四的直接下属，李四负责华东大区。
            TRIPLES:
              (张三, 隶属于, 李四)
              (李四, 负责, 华东大区)

            # Example 2 — one sentence, multiple relations
            TEXT: 订单 #1001 由张三处理，发往上海仓。
            TRIPLES:
              (张三, 处理, 订单 #1001)
              (订单 #1001, 发往, 上海仓)

            # Example 3 — COUNTER-example: no triple to extract
            TEXT: 这是一款非常优秀、广受好评的产品。
            TRIPLES: (empty — "优秀/广受好评" are modifiers, not relationships between entities)
            """)
    @UserMessage("""
            TEXT:
            {{text}}
            {{relationHint}}
            Extract the triples.
            """)
    ExtractedTriples extract(@V("text") String text, @V("relationHint") String relationHint);
}
