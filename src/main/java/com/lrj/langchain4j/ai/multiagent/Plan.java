package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

public record Plan(
        @Description("""
                1 to 6 independent sub-tasks that, taken together, fully answer the user's question.
                Use exactly 1 task for trivial / single-aspect questions; use more only when the
                question has multiple genuinely orthogonal aspects.
                """)
        List<SubTask> tasks
) {}
