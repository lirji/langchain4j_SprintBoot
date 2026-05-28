package com.lrj.langchain4j.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.ai.extract.Extractor;
import com.lrj.langchain4j.ai.extract.Ticket;
import com.lrj.langchain4j.ai.grounding.GroundingService;
import com.lrj.langchain4j.ai.multiagent.MultiAgentService;
import com.lrj.langchain4j.ai.reflexion.ReflexiveService;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.rag.RagIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Assistant assistant;
    private final ResolvedAssistantStyle assistantProps;
    private final GroundingService groundingService;
    private final Judge judge;
    private final Extractor extractor;
    private final MultiAgentService multiAgentService;
    private final ReflexiveService reflexiveService;
    private final Executor evalExecutor;
    private final RagIngestionService ragIngestionService;
    private final boolean autoIngest;
    /** Lazy 一次性 ingest 标记 —— 第一次 run 时触发，之后不重复。 */
    private final AtomicBoolean ingested = new AtomicBoolean(false);

    public EvaluationRunner(Assistant assistant,
                            ResolvedAssistantStyle assistantProps,
                            GroundingService groundingService,
                            Judge judge,
                            Extractor extractor,
                            MultiAgentService multiAgentService,
                            ReflexiveService reflexiveService,
                            @Qualifier("evalExecutor") Executor evalExecutor,
                            RagIngestionService ragIngestionService,
                            @Value("${app.eval.auto-ingest:false}") boolean autoIngest) {
        this.assistant = assistant;
        this.assistantProps = assistantProps;
        this.groundingService = groundingService;
        this.judge = judge;
        this.extractor = extractor;
        this.multiAgentService = multiAgentService;
        this.reflexiveService = reflexiveService;
        this.evalExecutor = evalExecutor;
        this.ragIngestionService = ragIngestionService;
        this.autoIngest = autoIngest;
    }

    public EvalResult.Summary runDefault() throws IOException {
        return run(loadCasesFromClasspath("eval/eval-cases.json"), 1);
    }

    public EvalResult.Summary runDefault(int runs) throws IOException {
        return run(loadCasesFromClasspath("eval/eval-cases.json"), runs);
    }

    public EvalResult.Summary run(List<EvalCase> cases) {
        return run(cases, 1);
    }

    public EvalResult.Summary run(List<EvalCase> cases, int runs) {
        if (runs < 1) throw new IllegalArgumentException("runs must be >= 1, got " + runs);

        // Lazy 一次性 ingest —— 第一次 run 时触发，避免 RAG case 召回空导致假 fail。
        // 用 compareAndSet 保证并发触发也只跑一次；ingest 失败也记下，不再重试（不阻塞主流程）。
        if (autoIngest && ingested.compareAndSet(false, true)) {
            try {
                int n = ragIngestionService.ingestFromConfiguredDir();
                log.info("auto-ingested {} documents before first eval run", n);
            } catch (Exception e) {
                log.warn("auto-ingest failed; RAG cases may return empty results", e);
            }
        }

        long totalStart = System.currentTimeMillis();
        String today = LocalDate.now().toString();

        // 每个 case 并行（独占 evalExecutor 的一根 thread），case 内部 N 个 run 仍顺序。
        // 这样 chatMemory chatId 隔离天然成立，不需要额外同步。
        List<CompletableFuture<EvalResult.CaseAggregate>> futures = cases.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> runCase(c, today, runs), evalExecutor))
                .toList();
        List<EvalResult.CaseAggregate> aggregates = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        int totalRuns = cases.size() * runs;
        int passedRuns = aggregates.stream().mapToInt(EvalResult.CaseAggregate::passedCount).sum();
        double avgScore = aggregates.stream()
                .flatMap(a -> a.attempts().stream())
                .mapToDouble(r -> r.judgment().score())
                .average().orElse(0.0);
        long totalDur = System.currentTimeMillis() - totalStart;

        return new EvalResult.Summary(
                cases.size(), runs, totalRuns, passedRuns,
                totalRuns == 0 ? 0.0 : (double) passedRuns / totalRuns,
                avgScore, totalDur, aggregates);
    }

    private EvalResult.CaseAggregate runCase(EvalCase c, String today, int runs) {
        List<EvalResult> attempts = new ArrayList<>(runs);
        for (int run = 1; run <= runs; run++) {
            attempts.add(runOnce(c, today, run));
        }
        EvalResult.CaseAggregate agg = EvalResult.CaseAggregate.from(c.id(), c.question(), attempts);
        log.info("case {} [{}] {}/{} passed, avg={} σ={}",
                c.id(), c.effectiveType(), agg.passedCount(), agg.runs(), agg.avgScore(), agg.scoreStdev());
        return agg;
    }

    private EvalResult runOnce(EvalCase c, String today, int runIndex) {
        long start = System.currentTimeMillis();
        String answer;
        try {
            answer = invokeByType(c, runIndex);
        } catch (Exception e) {
            log.error("case {} [{}] run {} threw", c.id(), c.effectiveType(), runIndex, e);
            answer = "[error: " + e.getMessage() + "]";
        }
        long dur = System.currentTimeMillis() - start;

        List<String> mustInclude = c.mustInclude() == null ? List.of() : c.mustInclude();
        List<String> mustNotInclude = c.mustNotInclude() == null ? List.of() : c.mustNotInclude();
        boolean ruleCovers = mustInclude.stream().allMatch(answer::contains);
        boolean ruleViolates = mustNotInclude.stream().anyMatch(answer::contains);

        Judgment judgeOutput;
        try {
            judgeOutput = judge.judge(today, c.question(), answer,
                    String.join(", ", mustInclude),
                    String.join(", ", mustNotInclude),
                    c.judgeHint() == null ? "" : c.judgeHint());
        } catch (Exception e) {
            log.error("judge threw on case {} run {}", c.id(), runIndex, e);
            judgeOutput = new Judgment(0.0, false, false, "judge error: " + e.getMessage());
        }
        Judgment j = new Judgment(judgeOutput.score(), ruleCovers, ruleViolates, judgeOutput.reasoning());
        boolean passed = ruleCovers && !ruleViolates && j.score() >= 0.6;
        return new EvalResult(c.id(), c.question(), answer, j, passed, dur);
    }

    /**
     * Type dispatch. 把不同 endpoint 的输出归一成 string 喂给 Judge 和规则匹配。
     */
    private String invokeByType(EvalCase c, int runIndex) {
        return switch (c.effectiveType()) {
            case "chat" -> invokeChat(c, runIndex);
            case "grounded" -> invokeGrounded(c, runIndex);
            case "extract" -> invokeExtract(c);
            case "multi-agent" -> invokeMultiAgent(c);
            case "reflexive" -> invokeReflexive(c);
            default -> throw new IllegalArgumentException(
                    "Unknown case type: " + c.type() + " (expected chat|grounded|extract|multi-agent|reflexive)");
        };
    }

    private String invokeChat(EvalCase c, int runIndex) {
        String chatId = "eval-" + c.id() + "-r" + runIndex + "-" + UUID.randomUUID();
        return assistant.chat(chatId,
                assistantProps.getLanguage(),
                assistantProps.getTone(),
                assistantProps.getCitationPolicy(),
                assistantProps.getExtra(),
                c.question());
    }

    /**
     * 跟 {@link #invokeChat} 一样调 {@code Assistant.chat}，但<strong>包一层 {@link GroundingService}</strong>，
     * 跟 controller 的 {@code /chat} 路径一致 —— 否则 grounding 的 {@code ⚠️ 可信度提示} 不会出现，
     * 这类 case 测不到闸门。仅当 {@code app.rag.grounding.enabled=true} 时闸门才真正运行；
     * 关闭时这里等价于 {@code invokeChat}（直通）。
     *
     * <p>注意：grounding case 依赖检索召回，需 {@code app.eval.auto-ingest=true} 才有 source 可校验。
     */
    private String invokeGrounded(EvalCase c, int runIndex) {
        String chatId = "eval-" + c.id() + "-r" + runIndex + "-" + UUID.randomUUID();
        return groundingService.applyToFreshAnswer(() -> assistant.chat(chatId,
                assistantProps.getLanguage(),
                assistantProps.getTone(),
                assistantProps.getCitationPolicy(),
                assistantProps.getExtra(),
                c.question()));
    }

    /** Extract Ticket POJO 序列化成 JSON 喂 Judge；保证 priority/category 等可被 mustInclude 字面匹配。 */
    private String invokeExtract(EvalCase c) {
        Ticket t = extractor.extractTicket(c.question());
        try {
            return JSON.writeValueAsString(t);
        } catch (JsonProcessingException e) {
            // Ticket 是 record，正常情况下不会序列化失败
            throw new IllegalStateException("Failed to serialize Ticket", e);
        }
    }

    /**
     * Multi-agent 输出三段：plan 任务数 + 子任务列表（含 deps 标注） + finalAnswer。
     * mustInclude 可以查 "tasks: 3"（拆分粒度）、"[deps: t1]"（验证 DAG 用对）、finalAnswer 内容。
     */
    private String invokeMultiAgent(EvalCase c) {
        MultiAgentService.Run run = multiAgentService.run(c.question());
        String taskList = run.plan().tasks().stream()
                .map(t -> {
                    String depsTag = t.effectiveDependsOn().isEmpty()
                            ? ""
                            : " [deps: " + String.join(",", t.effectiveDependsOn()) + "]";
                    return "  - " + t.id() + depsTag + ": " + t.description();
                })
                .collect(Collectors.joining("\n"));
        return "tasks: " + run.plan().tasks().size() + "\n"
                + taskList + "\n"
                + "---\n"
                + run.finalAnswer();
    }

    /** Reflexive 输出最终答案 + attempts 数（看是否真触发了改进）。 */
    private String invokeReflexive(EvalCase c) {
        ReflexiveService.Result r = reflexiveService.chatReflexive(c.question());
        return "attempts: " + r.attempts().size()
                + ", accepted: " + r.acceptedByThreshold() + "\n---\n"
                + r.finalAnswer();
    }

    public List<EvalCase> loadCasesFromClasspath(String path) throws IOException {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return JSON.readValue(in, new TypeReference<>() {});
        }
    }
}
