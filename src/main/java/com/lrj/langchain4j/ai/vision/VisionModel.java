package com.lrj.langchain4j.ai.vision;

/**
 * 视觉模型抽象：把「一张图 + 一段指令」喂给多模态 LLM，拿回文本。
 *
 * <p>刻意做成<strong>自定义接口而非直接暴露 {@code dev.langchain4j.model.chat.ChatModel} Bean</strong>——
 * LangChain4j 的 {@code @AiService} 自动发现按类型枚举 {@code ChatModel} Bean，多于 1 个就抛
 * conflict（{@code @Primary} / {@code autowireCandidate=false} 都不顶用，见 {@code LlmConfig}
 * 与 CLAUDE.md 注意事项）。所以 {@link DefaultVisionModel} 内部持有的 vision {@code ChatModel}
 * 由 {@code VisionConfig} 直接构造、<strong>不注册成 Bean</strong>，跟 {@code buildJudgeChatModel} 同思路。
 */
public interface VisionModel {

    /**
     * 看图并按配置的 caption/OCR 指令产出文本（图像描述 + 可见文字转写）。
     * 用于<strong>文档入库</strong>：图片/扫描件 → 文本 → 走现有 RAG 全链。
     *
     * @param image    图片原始字节
     * @param mimeType MIME（如 {@code image/png}）
     * @return 模型产出的描述/转写正文
     */
    String caption(byte[] image, String mimeType);

    /**
     * 看图并回答用户的具体问题。用于<strong>视觉对话</strong>（{@code POST /chat/vision}）——
     * 直接看图作答，不入库。
     *
     * @param image    图片原始字节
     * @param mimeType MIME
     * @param question 用户问题
     * @return 模型回答
     */
    String answer(byte[] image, String mimeType, String question);
}
