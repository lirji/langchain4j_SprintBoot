package com.lrj.langchain4j.store.redis;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed {@link ChatMemoryStore}. Messages are JSON-serialized via LangChain4j's
 * {@link ChatMessageSerializer}/{@link ChatMessageDeserializer} and stored under
 * {@code <prefix><memoryId>} with a TTL refreshed on every write.
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisChatMemoryStore(StringRedisTemplate redis, String keyPrefix, Duration ttl) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redis.opsForValue().get(key(memoryId));
        return json == null ? new ArrayList<>() : ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        redis.opsForValue().set(key(memoryId), json, ttl);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return keyPrefix + memoryId;
    }
}
