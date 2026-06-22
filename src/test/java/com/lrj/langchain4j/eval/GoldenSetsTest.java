package com.lrj.langchain4j.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性校验所有黄金集 JSON：能解析、id 在集内唯一、type 合法、question 非空。
 * 不连模型 —— 纯结构校验，CI 用来挡 JSON 笔误（漏逗号 / 写错 type / 重复 id）。
 */
class GoldenSetsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VALID_TYPES = Set.of(
            "chat", "graph", "grounded", "extract", "multi-agent", "reflexive", "sql", "a2a", "workflow", "agent");

    private List<EvalCase> load(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("resource exists: " + resource).isNotNull();
            return JSON.readValue(in, new TypeReference<>() {});
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "eval/eval-cases.json",
            "eval/eval-cases-sql.json",
            "eval/eval-cases-a2a.json",
            "eval/eval-cases-workflow.json",
            "eval/eval-cases-graph.json",
            "eval/eval-cases-agent.json"
    })
    void goldenSetIsWellFormed(String resource) throws Exception {
        List<EvalCase> cases = load(resource);
        assertThat(cases).as("non-empty: " + resource).isNotEmpty();

        Set<String> ids = new HashSet<>();
        for (EvalCase c : cases) {
            assertThat(c.id()).as("id present in " + resource).isNotBlank();
            assertThat(ids.add(c.id())).as("id unique: " + c.id() + " in " + resource).isTrue();
            assertThat(c.question()).as("question present: " + c.id()).isNotNull();
            assertThat(VALID_TYPES).as("valid type for " + c.id() + ": " + c.effectiveType())
                    .contains(c.effectiveType());
        }
    }

    @Test
    void typedSetsUseTheirType() throws Exception {
        assertThat(load("eval/eval-cases-sql.json")).allMatch(c -> "sql".equals(c.effectiveType()));
        assertThat(load("eval/eval-cases-a2a.json")).allMatch(c -> "a2a".equals(c.effectiveType()));
        assertThat(load("eval/eval-cases-workflow.json")).allMatch(c -> "workflow".equals(c.effectiveType()));
        assertThat(load("eval/eval-cases-graph.json")).allMatch(c -> "graph".equals(c.effectiveType()));
        assertThat(load("eval/eval-cases-agent.json")).allMatch(c -> "agent".equals(c.effectiveType()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eval/baseline.json", "eval/baseline-graph.json"})
    void baselineParsesAndHasSaneFloors(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("resource exists: " + resource).isNotNull();
            Baseline base = JSON.readValue(in, Baseline.class);
            assertThat(base.minOverallPassRate()).isBetween(0.0, 1.0);
            assertThat(base.minAverageScore()).isBetween(0.0, 1.0);
            assertThat(base.safeCases()).isNotNull();
        }
    }
}
