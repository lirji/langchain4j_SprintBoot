package com.lrj.langchain4j.async.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** HMAC-SHA256 签名计算。客户端用同样 secret + 收到的 body 计算后对比，防止 webhook URL 泄露导致的伪造。 */
public final class WebhookSigner {

    private WebhookSigner() {}

    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
