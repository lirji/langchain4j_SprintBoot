package com.lrj.langchain4j.rag.graph;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 从用户 query 里抽实体提及，给 {@link LlmEntityLinker} 做种子链接（{@code entity-linking=llm}）。
 * 比 token 子串匹配更能处理改写/同义/口语化提问；走 temp=0 判官模型。
 */
public interface QueryEntityExtractor {

    @SystemMessage("""
            Extract the named entities the user is asking about, from their question.
            Return ONLY entity names (people, orgs, products, IDs, places) — no relations,
            no verbs, no commentary. If the question mentions no concrete entity, return empty.
            """)
    @UserMessage("{{query}}")
    QueryEntities extract(@V("query") String query);
}
