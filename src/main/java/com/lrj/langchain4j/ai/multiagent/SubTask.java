package com.lrj.langchain4j.ai.multiagent;

import dev.langchain4j.model.output.structured.Description;

public record SubTask(
        @Description("Short stable id like t1, t2, t3 — used for referencing in results")
        String id,

        @Description("Self-contained instruction for a worker. Must be answerable without seeing other tasks.")
        String description
) {}
