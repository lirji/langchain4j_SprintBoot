package com.lrj.langchain4j.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.ai.agent.DeepAgentService;
import com.lrj.langchain4j.ai.extract.Extractor;
import com.lrj.langchain4j.ai.extract.Ticket;
import com.lrj.langchain4j.ai.grounding.GroundingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.langchain4j.a2a.A2aService;
import com.lrj.langchain4j.a2a.protocol.A2aMessage;
import com.lrj.langchain4j.a2a.protocol.JsonRpcResponse;
import com.lrj.langchain4j.a2a.protocol.MessageSendParams;
import com.lrj.langchain4j.a2a.protocol.Part;
import com.lrj.langchain4j.ai.multiagent.MultiAgentService;
import com.lrj.langchain4j.ai.reflexion.ReflexiveService;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import com.lrj.langchain4j.nl2sql.NlToSqlService;
import com.lrj.langchain4j.rag.RagIngestionService;
import com.lrj.langchain4j.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    // 三个落地功能的 endpoint 服务：都条件化装配（nl2sql/workflow 默认关，a2a 不带 demo 库时也可关），
    // 用 ObjectProvider 软依赖 —— 没启用对应 profile 时为 null，跑到对应 type 的 case 才报清晰错误。
    private final ObjectProvider<NlToSqlService> nlToSqlProvider;
    private final ObjectProvider<A2aService> a2aProvider;
    private final ObjectProvider<WorkflowService> workflowProvider;
    private final ObjectProvider<DeepAgentService> deepAgentProvider;
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
                            ObjectProvider<NlToSqlService> nlToSqlProvider,
                            ObjectProvider<A2aService> a2aProvider,
                            ObjectProvider<WorkflowService> workflowProvider,
                            ObjectProvider<DeepAgentService> deepAgentProvider,
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
        this.nlToSqlProvider = nlToSqlProvider;
        this.a2aProvider = a2aProvider;
        this.workflowProvider = workflowProvider;
        this.deepAgentProvider = deepAgentProvider;
        this.autoIngest = autoIngest;
    }

    public EvalResult.Summary runDefault() throws IOException {
        return run(loadCasesFromClasspath("eval/eval-cases.json"), 1);
    }

    public EvalResult.Summary runDefault(int runs) throws IOException {
        return run(loadCasesFromClasspath("eval/eval-cases.json"), runs);
    }

    /**
     * 跑命名黄金集：{@code default}（或 null/blank）→ {@code eval/eval-cases.json}（chat 主集，无需额外 profile）；
     * 其余 → {@code eval/eval-cases-<set>.json}（如 {@code sql} / {@code a2a} / {@code workflow}，
     * <strong>需先开对应 profile</strong>：{@code app.nl2sql.enabled} / {@code app.a2a.enabled} /
     * {@code app.workflow.enabled} + MySQL）。集名只允许 {@code [a-z0-9-]}，防路径穿越。
     */
    public EvalResult.Summary runSet(String set, int runs) throws IOException {
        String file;
        if (set == null || set.isBlank() || "default".equalsIgnoreCase(set)) {
            file = "eval/eval-cases.json";
        } else {
            String safe = set.trim().toLowerCase();
            if (!safe.matches("[a-z0-9-]+")) {
                throw new IllegalArgumentException("Invalid set name: " + set + " (allowed: [a-z0-9-]+)");
            }
            file = "eval/eval-cases-" + safe + ".json";
        }
        return run(loadCasesFromClasspath(file), runs);
    }

    /**
     * CI 门禁：跑指定集 N 次 → 对照提交的基线 → 返回 {@link BaselineGate.GateResult}。
     * 基线文件按集名定位：{@code default} → {@code eval/baseline.json}；其余 → {@code eval/baseline-<set>.json}。
     */
    public BaselineGate.GateResult gate(String set, int runs) throws IOException {
        EvalResult.Summary summary = runSet(set, runs);
        return BaselineGate.evaluate(summary, loadBaseline(set));
    }

    /** 从一次实测 run 生成基线（观测值 − slack），供首次/重置基线时落盘提交。 */
    public Baseline deriveBaseline(String set, int runs, double slack) throws IOException {
        return BaselineGate.deriveBaseline(runSet(set, runs), slack);
    }

    private Baseline loadBaseline(String set) throws IOException {
        String file = (set == null || set.isBlank() || "default".equalsIgnoreCase(set))
                ? "eval/baseline.json"
                : "eval/baseline-" + set.trim().toLowerCase() + ".json";
        try (var in = new ClassPathResource(file).getInputStream()) {
            return JSON.readValue(in, Baseline.class);
        }
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
            // graph 跟 chat 同 dispatch —— GraphRAG 第三路在 app.rag.graph.enabled=true 时自动并入 router，
            // 独立 set（eval-cases-graph.json）只为隔离前置（建图 + 多跳 case）。关闭时等价于普通 chat。
            case "graph" -> invokeChat(c, runIndex);
            case "grounded" -> invokeGrounded(c, runIndex);
            case "extract" -> invokeExtract(c);
            case "multi-agent" -> invokeMultiAgent(c);
            case "reflexive" -> invokeReflexive(c);
            case "sql" -> invokeSql(c);
            case "a2a" -> invokeA2a(c);
            case "workflow" -> invokeWorkflow(c, runIndex);
            case "agent" -> invokeAgent(c);
            default -> throw new IllegalArgumentException(
                    "Unknown case type: " + c.type()
                    + " (expected chat|graph|grounded|extract|multi-agent|reflexive|sql|a2a|workflow)");
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

    /**
     * NL2SQL：输出 guardBlocked + 生成的 SQL + 行数 + 自然语言解读。mustInclude 可查
     * {@code guardBlocked: true}（注入/越权被拦）、SQL 关键字（{@code SELECT}/表名/{@code tenant_id}）、
     * 或解读里的数字。需 {@code app.nl2sql.enabled=true} + MySQL demo 库 + tool-calling 模型。
     */
    private String invokeSql(EvalCase c) {
        NlToSqlService svc = nlToSqlProvider.getIfAvailable();
        if (svc == null) {
            throw new IllegalStateException(
                    "NL2SQL disabled — set app.nl2sql.enabled=true to run type:sql case " + c.id());
        }
        NlToSqlService.Result r = svc.ask(c.question());
        return "guardBlocked: " + r.guardBlocked() + "\n"
                + "sql: " + (r.sql() == null ? "(none)" : r.sql()) + "\n"
                + "rowCount: " + r.rowCount() + "\n---\n"
                + (r.answer() == null ? "" : r.answer());
    }

    /**
     * A2A：经 {@code message/send}（chat skill 同步路径）发问，把 JSON-RPC response 序列化喂 Judge。
     * 测的是 A2A dispatch + skill 路由 + 协议映射这一层（response 里含 agent message 的 reply text）。
     * 需 {@code app.a2a.enabled=true}。
     */
    private String invokeA2a(EvalCase c) {
        A2aService svc = a2aProvider.getIfAvailable();
        if (svc == null) {
            throw new IllegalStateException(
                    "A2A disabled — set app.a2a.enabled=true to run type:a2a case " + c.id());
        }
        A2aMessage msg = new A2aMessage("user", List.of(Part.text(c.question())),
                UUID.randomUUID().toString(), null, "eval-" + c.id(), null);
        JsonNode params = JSON.valueToTree(new MessageSendParams(msg, null, null));
        JsonRpcResponse resp = svc.dispatch("message/send", params, "eval-" + c.id());
        try {
            return JSON.writeValueAsString(resp);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize A2A response", e);
        }
    }

    /**
     * Workflow：启动退款审批流，输出 status + priority + reply。mustInclude 可查
     * {@code status: }（如人工审批挂起 / 自动答复）、{@code priority: HIGH}、reply 内容。
     * 需 {@code app.workflow.enabled=true} + MySQL（Flowable 持久化）。runIndex 进 chatId 保证幂等键不撞。
     */
    private String invokeWorkflow(EvalCase c, int runIndex) {
        WorkflowService svc = workflowProvider.getIfAvailable();
        if (svc == null) {
            throw new IllegalStateException(
                    "Workflow disabled — set app.workflow.enabled=true to run type:workflow case " + c.id());
        }
        String chatId = "eval-" + c.id() + "-r" + runIndex + "-" + UUID.randomUUID();
        WorkflowService.StartResult r = svc.start(chatId, c.question(), null, null);
        return "status: " + r.status() + "\n"
                + "priority: " + (r.priority() == null ? "(none)" : r.priority()) + "\n---\n"
                + (r.reply() == null ? "" : r.reply());
    }

    /**
     * Deep agent：把 goal 跑一遍开放式循环，输出 stopReason + 步数 + 最终答案。mustInclude 可查
     * {@code stopReason: DONE}（正常完成而非 LOOP/MAX_STEPS）、步数、或最终答案里的事实。
     * 需 {@code app.deep-agent.enabled=true} + tool-calling/JSON-schema 能力模型。
     */
    private String invokeAgent(EvalCase c) {
        DeepAgentService svc = deepAgentProvider.getIfAvailable();
        if (svc == null) {
            throw new IllegalStateException(
                    "Deep agent disabled — set app.deep-agent.enabled=true to run type:agent case " + c.id());
        }
        DeepAgentService.Run r = svc.run(c.question());
        return "stopReason: " + r.stopReason() + "\n"
                + "steps: " + r.steps().size() + "\n---\n"
                + (r.finalAnswer() == null ? "" : r.finalAnswer());
    }

    public List<EvalCase> loadCasesFromClasspath(String path) throws IOException {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return JSON.readValue(in, new TypeReference<>() {});
        }
    }
}
