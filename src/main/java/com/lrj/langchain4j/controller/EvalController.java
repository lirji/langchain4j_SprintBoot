package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.eval.Baseline;
import com.lrj.langchain4j.eval.BaselineGate;
import com.lrj.langchain4j.eval.EvalCase;
import com.lrj.langchain4j.eval.EvalResult;
import com.lrj.langchain4j.eval.EvaluationRunner;
import com.lrj.langchain4j.eval.retrieval.RetrievalEvaluator;
import com.lrj.langchain4j.eval.retrieval.RetrievalReport;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/eval")
public class EvalController {

    private final EvaluationRunner runner;
    private final RetrievalEvaluator retrievalEvaluator;

    public EvalController(EvaluationRunner runner, RetrievalEvaluator retrievalEvaluator) {
        this.runner = runner;
        this.retrievalEvaluator = retrievalEvaluator;
    }

    /**
     * 跑黄金集。{@code runs=N}（默认 1）让每个 case 跑 N 次，结果会聚合成
     * per-case {@code avg/σ/passRate}—— 用来过滤 Assistant 侧 temp=0.7 的随机性。
     * 真要做 prompt A/B 一般取 runs=3~5。
     *
     * <p>{@code set} 选黄金集：{@code default}（chat 主集，无需额外 profile）/ {@code sql} / {@code a2a} /
     * {@code workflow}（<strong>需先开对应 profile</strong>：app.nl2sql/a2a/workflow.enabled）。
     */
    @PostMapping("/run")
    @PreAuthorize("hasAuthority('SCOPE_eval')")
    public EvalResult.Summary run(@RequestParam(defaultValue = "1") int runs,
                                  @RequestParam(defaultValue = "default") String set) throws IOException {
        return runner.runSet(set, runs);
    }

    /** 同 {@link #run}，case 集从 body 来；脚本里搞临时回归用。 */
    @PostMapping("/run-cases")
    @PreAuthorize("hasAuthority('SCOPE_eval')")
    public EvalResult.Summary runCases(@RequestParam(defaultValue = "1") int runs,
                                       @RequestBody List<EvalCase> cases) {
        return runner.run(cases, runs);
    }

    /**
     * CI 门禁：跑指定集 → 对照提交的基线（{@code resources/eval/baseline[-set].json}）。
     * <strong>有回归时返回 HTTP 422</strong>，CI 脚本据此 fail；body 是 {@link BaselineGate.GateResult}
     * （含 regressions 明细 + 完整 summary）。无回归返回 200。
     */
    @PostMapping("/gate")
    @PreAuthorize("hasAuthority('SCOPE_eval')")
    public ResponseEntity<BaselineGate.GateResult> gate(@RequestParam(defaultValue = "1") int runs,
                                                        @RequestParam(defaultValue = "default") String set)
            throws IOException {
        BaselineGate.GateResult result = runner.gate(set, runs);
        return result.passed()
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    /**
     * 生成基线：跑指定集 N 次，每个门槛取观测值 − {@code slack}（默认 0.1）。把返回的 JSON 存成
     * {@code resources/eval/baseline[-set].json} 提交即可。首次建基线 / 有意抬高合格线后重置时用。
     * 推荐 {@code runs>=3} 让观测稳。
     */
    @PostMapping("/baseline")
    @PreAuthorize("hasAuthority('SCOPE_eval')")
    public Baseline baseline(@RequestParam(defaultValue = "3") int runs,
                             @RequestParam(defaultValue = "default") String set,
                             @RequestParam(defaultValue = "0.1") double slack) throws IOException {
        return runner.deriveBaseline(set, runs, slack);
    }

    /**
     * 检索质量评测（不经 LLM）：跑黄金集里每个 query 的向量召回，算 Recall@k / Precision@k / MRR / Hit@k。
     * 跟 {@link #run} 的 passRate（含 LLM 生成质量）互补 —— 这条<strong>只量检索器</strong>，
     * 调 chunking / embedding / rerank / min-score 后重跑，能把召回变化跟生成变化拆开归因。
     *
     * <p>{@code set} 选集（{@code default} → {@code eval/retrieval-cases.json}）；{@code ingest=true}
     * 先入库一次（默认 false —— 已 ingest 过则不必重复，且避免误改向量库）。
     */
    @PostMapping("/retrieval")
    @PreAuthorize("hasAuthority('SCOPE_eval')")
    public RetrievalReport.Summary retrieval(@RequestParam(defaultValue = "default") String set,
                                             @RequestParam(defaultValue = "false") boolean ingest)
            throws IOException {
        return retrievalEvaluator.runSet(set, ingest);
    }
}
