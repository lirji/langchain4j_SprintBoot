package com.lrj.langchain4j.config;

import com.lrj.langchain4j.memory.SummarizingChatMemory;
import com.lrj.langchain4j.store.redis.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 问答记忆系统配置
 */
@Configuration
public class ChatMemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.memory.store", havingValue = "in-memory", matchIfMissing = true)
    public ChatMemoryStore inMemoryChatMemoryStore() {
        log.info("ChatMemoryStore: in-memory (volatile)");
        // langchain4j 提供的会话记忆存储
        return new InMemoryChatMemoryStore();
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.store", havingValue = "redis")
    public ChatMemoryStore redisChatMemoryStore(StringRedisTemplate redis, MemoryProperties props) {
        log.info("ChatMemoryStore: redis (prefix={} ttl={})", props.getRedis().getKeyPrefix(), props.getRedis().getTtl());
        return new RedisChatMemoryStore(redis, props.getRedis().getKeyPrefix(), props.getRedis().getTtl());
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore store, MemoryProperties props,
                                                 LlmConfig llmConfig, LlmConfig.LlmProperties llmProperties) {
        String mode = props.getWindowMode();
        if ("tokens".equalsIgnoreCase(mode)) {
            TokenCountEstimator estimator = new OpenAiTokenCountEstimator(props.getTokenizerModel());
            int max = props.getMaxTokens();
            log.info("ChatMemory window: tokens (max={}, estimator~{})", max, props.getTokenizerModel());
            return memoryId -> TokenWindowChatMemory.builder()
                    .id(memoryId)
                    .maxTokens(max, estimator)
                    .chatMemoryStore(store)
                    .build();
        }
        if ("summary".equalsIgnoreCase(mode)) {
            int threshold = props.getMaxMessages();
            int keepRecent = props.getSummary().getKeepRecent();
            // 摘要走 temp=0 的专用模型（跟 Judge/Critic 同思路）：压缩是确定性任务，
            // 用主模型默认 temp=0.7 会让同一段历史每次压出不同摘要，记忆漂移。
            // 仅 summary 模式才构建，不注册成 Bean（避免多 ChatModel Bean 冲突）。
            ChatModel summarizer = llmConfig.buildJudgeChatModel(llmProperties);
            // 异步压缩（默认）：摘要 LLM 调用不在请求路径上跑，投到后台 daemon 线程池。
            // async=false 退回同步（在 add 内阻塞请求线程，用于调试/对照）。
            boolean async = props.getSummary().isAsync();
            Executor executor = async ? summaryCompactionExecutor() : null;
            // token 计量触发（maxTokens>0 时启用）+ 摘要膨胀上限。token 估算复用同款 OpenAI tokenizer。
            int maxTokens = props.getSummary().getMaxTokens();
            int maxSummaryChars = props.getSummary().getMaxSummaryChars();
            TokenCountEstimator estimator = maxTokens > 0
                    ? new OpenAiTokenCountEstimator(props.getTokenizerModel()) : null;
            log.info("ChatMemory window: summary (threshold={}, keepRecent={}, summarizer temp=0, async={}, maxTokens={}, maxSummaryChars={})",
                    threshold, keepRecent, async, maxTokens, maxSummaryChars);
            return memoryId -> new SummarizingChatMemory(memoryId, store, summarizer, threshold, keepRecent,
                    executor, estimator, maxTokens, maxSummaryChars);
        }
        int max = props.getMaxMessages();
        log.info("ChatMemory window: messages (max={})", max);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(max)
                .chatMemoryStore(store)
                .build();
    }

    /** 后台压缩线程池：daemon 小池（2 线程），不阻塞应用 shutdown。摘要是低频后台操作，2 线程足够。 */
    private volatile Executor summaryExecutor;

    private synchronized Executor summaryCompactionExecutor() {
        if (summaryExecutor == null) {
            summaryExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "mem-summarizer");
                t.setDaemon(true);
                return t;
            });
        }
        return summaryExecutor;
    }

    @Bean
    @ConfigurationProperties(prefix = "app.memory")
    public MemoryProperties memoryProperties() {
        return new MemoryProperties();
    }

    public static class MemoryProperties {
        private String store = "in-memory";
        private String windowMode = "messages";
        private int maxMessages = 20;
        private int maxTokens = 2000;
        private String tokenizerModel = "gpt-4o-mini";
        private Redis redis = new Redis();
        private Summary summary = new Summary();

        public String getStore() { return store; }
        public void setStore(String store) { this.store = store; }
        public String getWindowMode() { return windowMode; }
        public void setWindowMode(String windowMode) { this.windowMode = windowMode; }
        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public String getTokenizerModel() { return tokenizerModel; }
        public void setTokenizerModel(String tokenizerModel) { this.tokenizerModel = tokenizerModel; }
        public Redis getRedis() { return redis; }
        public void setRedis(Redis redis) { this.redis = redis; }
        public Summary getSummary() { return summary; }
        public void setSummary(Summary summary) { this.summary = summary; }

        public static class Summary {
            private int keepRecent = 6;
            /** 压缩是否异步（后台线程池跑摘要 LLM，不阻塞请求）。false = 同步（请求路径上压缩）。 */
            private boolean async = true;
            /** token 触发预算：>0 时除条数阈值外，token 数超此值也触发压缩（治少量超大消息）。0 = 关。 */
            private int maxTokens = 0;
            /** 摘要膨胀上限（字符）：压出的摘要超此长度截断兜底，防多轮累积越滚越大。0 = 不限。 */
            private int maxSummaryChars = 2000;

            public int getKeepRecent() { return keepRecent; }
            public void setKeepRecent(int keepRecent) { this.keepRecent = keepRecent; }
            public boolean isAsync() { return async; }
            public void setAsync(boolean async) { this.async = async; }
            public int getMaxTokens() { return maxTokens; }
            public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
            public int getMaxSummaryChars() { return maxSummaryChars; }
            public void setMaxSummaryChars(int maxSummaryChars) { this.maxSummaryChars = maxSummaryChars; }
        }

        public static class Redis {
            private String keyPrefix = "chat:mem:";
            private Duration ttl = Duration.ofHours(24);

            public String getKeyPrefix() { return keyPrefix; }
            public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
            public Duration getTtl() { return ttl; }
            public void setTtl(Duration ttl) { this.ttl = ttl; }
        }
    }
}
