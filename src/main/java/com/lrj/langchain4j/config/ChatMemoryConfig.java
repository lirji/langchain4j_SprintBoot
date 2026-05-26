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
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore store, MemoryProperties props, ChatModel chatModel) {
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
            log.info("ChatMemory window: summary (threshold={}, keepRecent={})", threshold, keepRecent);
            return memoryId -> new SummarizingChatMemory(memoryId, store, chatModel, threshold, keepRecent);
        }
        int max = props.getMaxMessages();
        log.info("ChatMemory window: messages (max={})", max);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(max)
                .chatMemoryStore(store)
                .build();
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

            public int getKeepRecent() { return keepRecent; }
            public void setKeepRecent(int keepRecent) { this.keepRecent = keepRecent; }
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
