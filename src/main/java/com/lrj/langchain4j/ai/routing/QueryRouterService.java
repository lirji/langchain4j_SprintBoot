package com.lrj.langchain4j.ai.routing;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.config.ResolvedAssistantStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 三阶段流水线：classify → dispatch → answer。
 *
 * <ul>
 *   <li>RAG → 全功能 {@link Assistant}（走 RetrievalAugmentor）</li>
 *   <li>TOOL / CHAT → {@link BareAssistant}（跳过 RAG，仅 chat + tools）</li>
 * </ul>
 *
 * <p>跟 {@link com.lrj.langchain4j.config.QueryRoutingConfig} 一样按 {@code app.query-router.enabled}
 * 条件装配，关掉时整套不存在，不影响默认 /chat 路径。
 */
@Service
@ConditionalOnProperty(name = "app.query-router.enabled", havingValue = "true")
public class QueryRouterService {

    private static final Logger log = LoggerFactory.getLogger(QueryRouterService.class);

    private final QueryClassifier classifier;
    private final Assistant assistant;
    private final BareAssistant bareAssistant;
    private final ResolvedAssistantStyle props;

    public QueryRouterService(QueryClassifier classifier,
                              Assistant assistant,
                              BareAssistant bareAssistant,
                              ResolvedAssistantStyle props) {
        this.classifier = classifier;
        this.assistant = assistant;
        this.bareAssistant = bareAssistant;
        this.props = props;
    }

    public record RoutedReply(RouteDecision decision, String reply, long classifyMs, long answerMs) {}

    public RoutedReply route(String chatId, String message) {
        long t0 = System.nanoTime();
        RouteDecision decision;
        try {
            decision = classifier.classify(message);
        } catch (Exception e) {
            log.warn("classifier threw, falling back to RAG path", e);
            decision = new RouteDecision(RouteKind.RAG, "classifier error fallback");
        }
        long classifyMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        String reply = switch (decision.kind()) {
            case RAG -> assistant.chat(chatId,
                    props.getLanguage(), props.getTone(),
                    props.getCitationPolicy(), props.getExtra(), message);
            // TOOL / CHAT 都走 BareAssistant —— 同一个变种就够了，无需再分
            case TOOL, CHAT -> bareAssistant.chat(chatId,
                    props.getLanguage(), props.getTone(),
                    props.getCitationPolicy(), props.getExtra(), message);
        };
        long answerMs = (System.nanoTime() - t1) / 1_000_000;

        log.info("routed [{}] reason={} classify={}ms answer={}ms",
                decision.kind(), decision.reason(), classifyMs, answerMs);
        return new RoutedReply(decision, reply, classifyMs, answerMs);
    }
}
