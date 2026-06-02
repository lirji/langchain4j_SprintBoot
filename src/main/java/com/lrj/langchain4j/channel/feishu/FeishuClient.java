package com.lrj.langchain4j.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 飞书出站客户端（M1.B 渠道出站）：管 {@code tenant_access_token} 缓存刷新 + 主动发消息 / 卡片。
 *
 * <p><b>token 缓存</b>：飞书的 {@code tenant_access_token} 有效期约 2h，频繁取会被限频。用
 * {@link AtomicReference} 缓存 {@code (token, expiresAtMs)}，提前 5 分钟过期重取（并发下最坏多取一次，
 * 无副作用、可接受，不加锁）。
 *
 * <p>HTTP 用 JDK 内置 {@link HttpClient}（项目已显式选 JDK 实现当 LangChain4j HTTP），不引新依赖。
 */
@Component
@ConditionalOnProperty(name = "app.channel.feishu.enabled", havingValue = "true")
public class FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    private final FeishuProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final AtomicReference<Cached> tokenCache = new AtomicReference<>();

    public FeishuClient(FeishuProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                .build();
    }

    /** 给某个用户（{@code open_id}）发一段文本。返回是否成功。 */
    public boolean sendText(String openId, String text) {
        try {
            String contentJson = mapper.writeValueAsString(Map.of("text", text));
            String body = mapper.writeValueAsString(Map.of(
                    "receive_id", openId,
                    "msg_type", "text",
                    "content", contentJson));
            return postWithToken("/open-apis/im/v1/messages?receive_id_type=open_id", body);
        } catch (Exception e) {
            log.warn("飞书发消息失败 openId={}：{}", openId, e.toString());
            return false;
        }
    }

    /** 给某个会话（{@code chat_id}）发一张交互卡片（{@code card} 为飞书卡片 JSON 字符串）。 */
    public boolean sendCardToChat(String chatId, String cardJson) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "receive_id", chatId,
                    "msg_type", "interactive",
                    "content", cardJson));
            return postWithToken("/open-apis/im/v1/messages?receive_id_type=chat_id", body);
        } catch (Exception e) {
            log.warn("飞书发卡片失败 chatId={}：{}", chatId, e.toString());
            return false;
        }
    }

    private boolean postWithToken(String path, String body) throws Exception {
        String token = token();
        if (token == null) {
            return false;
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .timeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        int code = json.path("code").asInt(-1);
        if (code != 0) {
            log.warn("飞书 API 返回非 0：path={} code={} msg={}", path, code, json.path("msg").asText());
            return false;
        }
        return true;
    }

    /** 取缓存中的 token；过期/无则刷新。 */
    private String token() {
        Cached c = tokenCache.get();
        long now = System.currentTimeMillis();
        if (c != null && now < c.expiresAtMs) {
            return c.token;
        }
        return refreshToken(now);
    }

    private String refreshToken(long now) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "app_id", props.getAppId(), "app_secret", props.getAppSecret()));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .timeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            JsonNode json = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            if (json.path("code").asInt(-1) != 0) {
                log.warn("拉 tenant_access_token 失败：{}", json.path("msg").asText());
                return null;
            }
            String token = json.path("tenant_access_token").asText();
            long expireSec = json.path("expire").asLong(7200);
            // 提前 5 分钟过期，留刷新余量
            tokenCache.set(new Cached(token, now + (expireSec - 300) * 1000));
            return token;
        } catch (Exception e) {
            log.warn("拉 tenant_access_token 异常：{}", e.toString());
            return null;
        }
    }

    private record Cached(String token, long expiresAtMs) {}
}
