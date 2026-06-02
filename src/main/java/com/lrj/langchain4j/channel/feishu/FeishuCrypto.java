package com.lrj.langchain4j.channel.feishu;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 飞书事件订阅 / 卡片回调的入站验签解密（M1.B 渠道入站第 1 个关键点）。全是静态纯函数，便于单测。
 *
 * <p><b>解密</b>（事件订阅开了「加密策略」时，回调 body 是 {@code {"encrypt":"<base64>"}}）：
 * <ol>
 *   <li>{@code key = SHA-256(encryptKey)}（32 字节，AES-256）；</li>
 *   <li>{@code cipher = base64decode(encrypt)}；前 16 字节是 IV，其余是密文；</li>
 *   <li>AES/CBC/PKCS5Padding 解出明文 JSON。</li>
 * </ol>
 *
 * <p><b>验签</b>（飞书在请求头带 {@code X-Lark-Request-Timestamp}/{@code X-Lark-Request-Nonce}/{@code X-Lark-Signature}）：
 * {@code signature = hex(SHA-256(timestamp + nonce + encryptKey + rawBody))}，比对一致才可信。
 * 用 {@link MessageDigest#isEqual} 常量时间比较防时序侧信道。
 */
public final class FeishuCrypto {

    private FeishuCrypto() {}

    /** AES-256-CBC 解密飞书 {@code encrypt} 字段，返回明文 JSON。 */
    public static String decrypt(String encryptKey, String encryptBase64) {
        try {
            byte[] aesKey = sha256(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] data = Base64.getDecoder().decode(encryptBase64);
            if (data.length <= 16) {
                throw new IllegalArgumentException("encrypt payload too short");
            }
            byte[] iv = Arrays.copyOfRange(data, 0, 16);
            byte[] cipherText = Arrays.copyOfRange(data, 16, data.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("飞书回调解密失败", e);
        }
    }

    /** 计算飞书事件签名：{@code hex(SHA-256(timestamp + nonce + encryptKey + rawBody))}。 */
    public static String signature(String timestamp, String nonce, String encryptKey, String rawBody) {
        String concat = timestamp + nonce + encryptKey + rawBody;
        return HexFormat.of().formatHex(sha256(concat.getBytes(StandardCharsets.UTF_8)));
    }

    /** 常量时间比对收到的签名与本地计算值。 */
    public static boolean verifySignature(String timestamp, String nonce, String encryptKey,
                                          String rawBody, String received) {
        if (received == null) {
            return false;
        }
        String expected = signature(timestamp, nonce, encryptKey, rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
