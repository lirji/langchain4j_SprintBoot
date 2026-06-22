package com.lrj.langchain4j.channel.feishu;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑单测：飞书入站验签解密。解密用「按飞书同方案在测里加密 → 解出还原」自洽验证算法接线
 * （key=sha256、IV 前置、AES/CBC/PKCS5）；验签用同公式算期望值对比。不连飞书。
 */
class FeishuCryptoTest {

    private static final String KEY = "test-encrypt-key-123";

    /** 按飞书方案加密：iv(16) ++ AES-CBC-PKCS5(sha256(key), iv, plaintext)，整体 base64。 */
    private static String encrypt(String key, String plaintext) throws Exception {
        byte[] aesKey = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    @Test
    void decrypt_roundTrip() throws Exception {
        String plain = "{\"type\":\"url_verification\",\"challenge\":\"abc123\",\"token\":\"t\"}";
        String enc = encrypt(KEY, plain);
        assertEquals(plain, FeishuCrypto.decrypt(KEY, enc));
    }

    @Test
    void decrypt_handlesUnicode() throws Exception {
        String plain = "{\"text\":\"我要退款，订单 #88231\"}";
        assertEquals(plain, FeishuCrypto.decrypt(KEY, encrypt(KEY, plain)));
    }

    @Test
    void verifySignature_acceptsCorrect() {
        String ts = "1719900000", nonce = "n-1", body = "{\"a\":1}";
        String sig = FeishuCrypto.signature(ts, nonce, KEY, body);
        assertTrue(FeishuCrypto.verifySignature(ts, nonce, KEY, body, sig));
    }

    @Test
    void verifySignature_rejectsTamperedBody() {
        String ts = "1719900000", nonce = "n-1", body = "{\"a\":1}";
        String sig = FeishuCrypto.signature(ts, nonce, KEY, body);
        assertFalse(FeishuCrypto.verifySignature(ts, nonce, KEY, "{\"a\":2}", sig));
        assertFalse(FeishuCrypto.verifySignature(ts, nonce, KEY, body, null));
    }
}
