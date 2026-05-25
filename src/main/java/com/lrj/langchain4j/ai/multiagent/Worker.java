package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface Worker {

    @SystemMessage("""
            You are a focused specialist. Execute exactly one sub-task and return a concise,
            self-contained answer. Do not ask follow-up questions; if information is missing,
            make a reasonable assumption and state it explicitly.
            """)
    @UserMessage("""
            Sub-task:
            {{it}}
            """)
    String execute(String taskDescription);
}
