package com.lrj.langchain4j.controller.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.channel.feishu.FeishuChannelService;
import com.lrj.langchain4j.channel.feishu.FeishuCrypto;
import com.lrj.langchain4j.channel.feishu.FeishuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 飞书事件订阅 / 卡片回调入口（M1.B 渠道入站）。仅在 {@code app.channel.feishu.enabled=true} 装配。
 * 路径 {@code /channel/feishu/**} 在 {@code SecurityConfig} 放行——飞书回调不带 {@code X-Api-Key}，
 * 用飞书自己的验签解密（{@link FeishuCrypto}）。
 *
 * <p>处理三类入站：
 * <ol>
 *   <li><b>URL 验证握手</b>（首次配置事件订阅）：{@code type=url_verification} → 原样回 {@code challenge}；</li>
 *   <li><b>消息事件</b>（{@code im.message.receive_v1}）：交 {@link FeishuChannelService} 异步处理，立刻 200（满足 ~5s ack）；</li>
 *   <li><b>卡片按钮回调</b>（审批通过/驳回）：交 {@link FeishuChannelService#handleCardAction} 推进工作流。</li>
 * </ol>
 *
 * <p><b>验签解密</b>：body 带 {@code encrypt} 字段（事件订阅开了加密）→ 先 AES 解密；配了
 * {@code encryptKey} 时校验 {@code X-Lark-Signature}；payload 里的 token 比对 {@code verificationToken}。
 */
@RestController
@RequestMapping("/channel/feishu")
@ConditionalOnProperty(name = "app.channel.feishu.enabled", havingValue = "true")
public class FeishuController {

    private static final Logger log = LoggerFactory.getLogger(FeishuController.class);

    private final FeishuProperties props;
    private final FeishuChannelService channelService;
    private final ObjectMapper mapper;

    public FeishuController(FeishuProperties props, FeishuChannelService channelService, ObjectMapper mapper) {
        this.props = props;
        this.channelService = channelService;
        this.mapper = mapper;
    }

    @PostMapping("/event")
    public ResponseEntity<?> event(@RequestBody String rawBody,
                                   @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String ts,
                                   @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
                                   @RequestHeader(value = "X-Lark-Signature", required = false) String sig) {
        try {
            // 加密模式：先验签，再解密
            if (props.getEncryptKey() != null && !props.getEncryptKey().isBlank()
                    && sig != null && ts != null && nonce != null
                    && !FeishuCrypto.verifySignature(ts, nonce, props.getEncryptKey(), rawBody, sig)) {
                log.warn("飞书回调验签失败，拒绝");
                return ResponseEntity.status(401).build();
            }
            JsonNode payload = decode(rawBody);

            // 1) URL 验证握手
            if ("url_verification".equals(payload.path("type").asText())) {
                if (!tokenOk(payload.path("token").asText(null))) {
                    return ResponseEntity.status(401).build();
                }
                return ResponseEntity.ok(Map.of("challenge", payload.path("challenge").asText("")));
            }

            // token 校验（事件 v2 在 header.token）
            String token = payload.path("header").path("token").asText(payload.path("token").asText(null));
            if (!tokenOk(token)) {
                return ResponseEntity.status(401).build();
            }

            String eventType = payload.path("header").path("event_type")
                    .asText(payload.path("event").path("type").asText(""));
            if ("im.message.receive_v1".equals(eventType)) {
                channelService.handleMessageEvent(payload); // @Async，立刻返回
            } else if (eventType.startsWith("card.action") || payload.path("action").has("value")) {
                channelService.handleCardAction(payload);
            } else {
                log.debug("飞书未处理的 event_type={}", eventType);
            }
            return ResponseEntity.ok(Map.of("code", 0));
        } catch (Exception e) {
            log.warn("飞书回调处理异常：{}", e.toString());
            // 返回 200 避免飞书反复重推；内部已记日志
            return ResponseEntity.ok(Map.of("code", 0));
        }
    }

    /** body 带 encrypt 字段则解密后解析，否则按明文解析。 */
    private JsonNode decode(String rawBody) throws Exception {
        JsonNode node = mapper.readTree(rawBody);
        if (node.hasNonNull("encrypt")) {
            String plain = FeishuCrypto.decrypt(props.getEncryptKey(), node.get("encrypt").asText());
            return mapper.readTree(plain);
        }
        return node;
    }

    /** verificationToken 没配则不校验（明文 demo）；配了则必须一致。 */
    private boolean tokenOk(String token) {
        String expected = props.getVerificationToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equals(token);
    }
}
