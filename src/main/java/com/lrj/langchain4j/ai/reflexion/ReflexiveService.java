package com.lrj.langchain4j.ai.reflexion;

import com.lrj.langchain4j.config.ReflexionConfig.ReflexionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reflexion loop: generate → critique → (if aggregate score below threshold) improve → critique again.
 *
 * <p>Aggregate score is a weighted average of correctness/completeness/clarity using
 * {@code app.reflexion.weights.*}. The {@code mainIssue} string is passed (with the
 * per-dim scores) to {@code Answerer.improve} as a concrete fix hint, instead of a
 * generic feedback blob.
 */
@Service
public class ReflexiveService {

    private static final Logger log = LoggerFactory.getLogger(ReflexiveService.class);

    private final Answerer answerer;
    private final Critic critic;
    private final ReflexionProperties props;

    public ReflexiveService(Answerer answerer, Critic critic, ReflexionProperties props) {
        this.answerer = answerer;
        this.critic = critic;
        this.props = props;
    }

    public record Attempt(int n,
                          String answer,
                          double aggregate,
                          double correctness,
                          double completeness,
                          double clarity,
                          String mainIssue) {}

    public record Result(String finalAnswer, List<Attempt> attempts, boolean acceptedByThreshold) {}

    public Result chatReflexive(String question) {
        List<Attempt> attempts = new ArrayList<>();
        String answer = answerer.answer(question);
        Critique c = critic.critique(question, answer);
        double agg = aggregate(c);
        attempts.add(toAttempt(1, answer, c, agg));
        log.info("attempt 1 agg={} corr={} comp={} clar={} issue={}",
                agg, c.correctness(), c.completeness(), c.clarity(), c.mainIssue());

        int n = 1;
        while (agg < props.getThreshold() && n < props.getMaxAttempts() + 1) {
            n++;
            answer = answerer.improve(question, answer, buildImproveHint(c));
            c = critic.critique(question, answer);
            agg = aggregate(c);
            attempts.add(toAttempt(n, answer, c, agg));
            log.info("attempt {} agg={} corr={} comp={} clar={} issue={}",
                    n, agg, c.correctness(), c.completeness(), c.clarity(), c.mainIssue());
        }
        return new Result(answer, attempts, agg >= props.getThreshold());
    }

    private double aggregate(Critique c) {
        ReflexionProperties.Weights w = props.getWeights();
        double sum = w.getCorrectness() + w.getCompleteness() + w.getClarity();
        if (sum <= 0) {
            // 配置异常时退化为等权，避免 NaN
            return (c.correctness() + c.completeness() + c.clarity()) / 3.0;
        }
        return (w.getCorrectness() * c.correctness()
                + w.getCompleteness() * c.completeness()
                + w.getClarity() * c.clarity()) / sum;
    }

    /** 把分数和 mainIssue 揉进一个 hint，让 improve 知道"哪些维度差 + 具体该改什么"。 */
    private String buildImproveHint(Critique c) {
        return String.format(
                "Reviewer scored: correctness=%.2f, completeness=%.2f, clarity=%.2f.%n"
                        + "Top issue to fix: %s",
                c.correctness(), c.completeness(), c.clarity(), c.mainIssue());
    }

    private static Attempt toAttempt(int n, String answer, Critique c, double agg) {
        return new Attempt(n, answer, agg,
                c.correctness(), c.completeness(), c.clarity(), c.mainIssue());
    }
}
