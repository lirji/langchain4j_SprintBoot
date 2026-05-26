package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 单任务执行者。
 *
 * <p>{@code upstream} 是上游任务的输出拼成的 string（{@link MultiAgentService} 负责拼）；
 * 没有上游时传空串，模板渲染为只有 task 部分，行为跟纯并行 fan-out 一致。
 */
public interface Worker {

    @SystemMessage("""
            You are a focused specialist. Execute exactly one sub-task and return a concise,
            self-contained answer. If upstream context is provided, USE it — do not re-derive
            facts already in the upstream output. Do not ask follow-up questions; if
            information is missing, make a reasonable assumption and state it explicitly.
            """)
    @UserMessage("""
            {{upstream}}

            Your sub-task:
            {{task}}
            """)
    String execute(@V("task") String task, @V("upstream") String upstream);
}
