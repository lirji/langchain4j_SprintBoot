package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.eval.EvalCase;
import com.lrj.langchain4j.eval.EvalResult;
import com.lrj.langchain4j.eval.EvaluationRunner;
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

    public EvalController(EvaluationRunner runner) {
        this.runner = runner;
    }

    /**
     * 跑黄金集。{@code runs=N}（默认 1）让每个 case 跑 N 次，结果会聚合成
     * per-case {@code avg/σ/passRate}—— 用来过滤 Assistant 侧 temp=0.7 的随机性。
     * 真要做 prompt A/B 一般取 runs=3~5。
     */
    @PostMapping("/run")
    public EvalResult.Summary run(@RequestParam(defaultValue = "1") int runs) throws IOException {
        return runner.runDefault(runs);
    }

    /** 同 {@link #run}，case 集从 body 来；脚本里搞临时回归用。 */
    @PostMapping("/run-cases")
    public EvalResult.Summary runCases(@RequestParam(defaultValue = "1") int runs,
                                       @RequestBody List<EvalCase> cases) {
        return runner.run(cases, runs);
    }
}
