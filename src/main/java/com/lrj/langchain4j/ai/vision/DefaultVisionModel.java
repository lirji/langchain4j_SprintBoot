package com.lrj.langchain4j.ai.vision;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link VisionModel} 的默认实现：把图片 base64 编码后，与指令一起拼成多模态
 * {@code UserMessage}，调用内部持有的视觉 {@code ChatModel}。
 *
 * <p>内部的 {@code ChatModel} 由 {@code VisionConfig} 构造传入，<strong>不是 Spring Bean</strong>
 * （避免与主 {@code ChatModel} 撞 {@code @AiService} 自动发现，见 {@link VisionModel} 注释）。
 *
 * <p>{@link #caption} 路径带<strong>按图内容 SHA-256 去重的有界 LRU 缓存</strong>：同一张图重复
 * 上传 / 多次入库直接复用上次结果，省掉重复（且昂贵）的视觉调用。{@link #answer} 不缓存——
 * 问题随每次请求变化，缓存无意义。
 *
 * <p>无状态对外（线程安全）：每次调用都是独立的一轮（不带 ChatMemory）。视觉对话刻意做成单轮——
 * 看图作答语义清晰、可重复；要多轮上下文可后续接 ChatMemory。
 */
public class DefaultVisionModel implements VisionModel {

    private static final Logger log = LoggerFactory.getLogger(DefaultVisionModel.class);

    private final ChatModel model;
    private final String captionPrompt;
    private final long maxImageBytes;

    /** 访问序 LRU，超过容量自动淘汰最久未用项。{@code synchronizedMap} 包一层保证并发安全。 */
    private final Map<String, String> captionCache;

    public DefaultVisionModel(ChatModel model, String captionPrompt, long maxImageBytes, int captionCacheSize) {
        this.model = model;
        this.captionPrompt = captionPrompt;
        this.maxImageBytes = maxImageBytes;
        this.captionCache = captionCacheSize <= 0 ? null : Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > captionCacheSize;
                    }
                });
    }

    @Override
    public String caption(byte[] image, String mimeType) {
        validate(image);
        String mime = normalizeMime(mimeType);
        if (captionCache == null) {
            return describe(image, mime, captionPrompt);
        }
        String key = sha256(image) + ":" + mime;
        String cached = captionCache.get(key);
        if (cached != null) {
            log.info("vision caption cache HIT (key={}…, chars={})", key.substring(0, 8), cached.length());
            return cached;
        }
        String text = describe(image, mime, captionPrompt);
        captionCache.put(key, text);
        return text;
    }

    @Override
    public String answer(byte[] image, String mimeType, String question) {
        validate(image);
        String q = (question == null || question.isBlank())
                ? "请描述这张图片，并转写其中的文字。"
                : question;
        return describe(image, normalizeMime(mimeType), q);
    }

    private void validate(byte[] image) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (image.length > maxImageBytes) {
            throw new IllegalArgumentException(
                    "image too large: " + image.length + " > " + maxImageBytes + " bytes");
        }
    }

    private String describe(byte[] image, String mime, String instruction) {
        String base64 = Base64.getEncoder().encodeToString(image);
        UserMessage msg = UserMessage.from(
                ImageContent.from(base64, mime),
                TextContent.from(instruction));
        long t0 = System.currentTimeMillis();
        String text = model.chat(msg).aiMessage().text();
        log.info("vision describe: bytes={} mime={} -> chars={} ({}ms)",
                image.length, mime, text == null ? 0 : text.length(), System.currentTimeMillis() - t0);
        return text == null ? "" : text.trim();
    }

    /** 缺失/非 image/* 的 content-type 兜底成 image/png（多数视觉端点对 png/jpeg 容忍度最高）。 */
    private static String normalizeMime(String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return mimeType;
        }
        return "image/png";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
