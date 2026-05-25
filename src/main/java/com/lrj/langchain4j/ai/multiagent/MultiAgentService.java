package com.lrj.langchain4j.ai.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class MultiAgentService {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentService.class);

    private final Planner planner;
    private final Worker worker;
    private final Synthesizer synthesizer;
    private final Executor executor;

    public MultiAgentService(Planner planner,
                             Worker worker,
                             Synthesizer synthesizer,
                             @Qualifier("multiAgentExecutor") Executor executor) {
        this.planner = planner;
        this.worker = worker;
        this.synthesizer = synthesizer;
        this.executor = executor;
    }

    public record WorkerResult(String taskId, String description, String result) {}

    public record Run(Plan plan, List<WorkerResult> workerResults, String finalAnswer) {}

    public Run run(String question) {
        Plan plan = planner.plan(question);
        log.info("planner produced {} sub-tasks", plan.tasks().size());

        List<CompletableFuture<WorkerResult>> futures = plan.tasks().stream()
                .map(t -> CompletableFuture.supplyAsync(
                        () -> new WorkerResult(t.id(), t.description(), worker.execute(t.description())),
                        executor))
                .toList();
        List<WorkerResult> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());

        String formatted = results.stream()
                .map(r -> "[" + r.taskId() + "] " + r.description() + "\n→ " + r.result())
                .collect(Collectors.joining("\n\n"));

        String finalAnswer = synthesizer.synthesize(question, formatted);
        return new Run(plan, results, finalAnswer);
    }
}
