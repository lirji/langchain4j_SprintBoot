package com.lrj.langchain4j.ai.reflexion;

import com.lrj.langchain4j.config.ReflexionConfig.ReflexionProperties;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * SSE 流式版本：按阶段 emit 事件。事件 names：
     * <ul>
     *   <li>{@code attempt-start} — 新一轮 attempt 开始（含序号）</li>
     *   <li>{@code answer-token} — Answerer 生成中的 token（第 1 轮是 answer，后续是 improve）</li>
     *   <li>{@code critique} — Critic 评分结果（含 mainIssue 和聚合分）</li>
     *   <li>{@code done} — 反思循环结束，附最终 Result</li>
     *   <li>{@code error} — 任何阶段异常</li>
     * </ul>
     *
     * <p>每一轮 Answerer 走 streaming 喂 token；Critic 评分仍同步（结构化输出不适合 stream）。
     * 用 CountDownLatch 把 streaming 转成阻塞拿全文，简化"等本轮 answer 完整 → 调 Critic"的同步。
     */
    public void chatReflexiveStream(String question, SseEmitter emitter) {
        try {
            List<Attempt> attempts = new ArrayList<>();
            safeSend(emitter, "attempt-start", 1);
            String answer = streamAndCollect(emitter, answerer.answerStream(question));
            Critique c = critic.critique(question, answer);
            double agg = aggregate(c);
            attempts.add(toAttempt(1, answer, c, agg));
            safeSend(emitter, "critique", toAttempt(1, answer, c, agg));
            log.info("stream attempt 1 agg={} issue={}", agg, c.mainIssue());

            int n = 1;
            while (agg < props.getThreshold() && n < props.getMaxAttempts() + 1) {
                n++;
                safeSend(emitter, "attempt-start", n);
                answer = streamAndCollect(emitter,
                        answerer.improveStream(question, answer, buildImproveHint(c)));
                c = critic.critique(question, answer);
                agg = aggregate(c);
                attempts.add(toAttempt(n, answer, c, agg));
                safeSend(emitter, "critique", toAttempt(n, answer, c, agg));
                log.info("stream attempt {} agg={} issue={}", n, agg, c.mainIssue());
            }

            safeSend(emitter, "done", new Result(answer, attempts, agg >= props.getThreshold()));
            emitter.complete();
        } catch (Exception e) {
            log.error("chatReflexiveStream error", e);
            safeSend(emitter, "error", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /**
     * 订阅 TokenStream，把 token 一边 emit 给 SSE 一边累积，complete 时阻塞返回全文。
     * 这样上层逻辑可以保持顺序（answer → critic → improve），不用引入 reactive 链。
     */
    private String streamAndCollect(SseEmitter emitter, TokenStream stream) throws InterruptedException {
        StringBuilder buf = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        stream
                .onPartialResponse(t -> {
                    buf.append(t);
                    safeSend(emitter, "answer-token", t);
                })
                .onCompleteResponse(r -> latch.countDown())
                .onError(t -> {
                    err.set(t);
                    latch.countDown();
                })
                .start();
        latch.await();
        if (err.get() != null) {
            throw new RuntimeException("answerer stream failed", err.get());
        }
        return buf.toString();
    }

    private static void safeSend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ignored) {
            // emitter 可能已关闭，由外层 try/catch 兜底
        }
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
