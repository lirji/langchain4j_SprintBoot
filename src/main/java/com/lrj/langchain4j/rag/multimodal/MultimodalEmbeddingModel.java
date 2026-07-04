package com.lrj.langchain4j.rag.multimodal;

/**
 * 原生多模态 embedding 抽象：把<strong>文本</strong>和<strong>图片</strong>都映射到<strong>同一个
 * 跨模态向量空间</strong>（CLIP / jina-clip），于是「文本 query 的向量」可以直接与「图片的向量」比
 * 余弦相似度 —— 这正是「文本 ↔ 图片」互检索的地基。
 *
 * <p>刻意做成<strong>自定义接口而非直接暴露 {@code dev.langchain4j.model.embedding.EmbeddingModel}
 * Bean</strong>：主 RAG 链已经装配了一个文本 {@code EmbeddingModel}（{@code EmbeddingModelConfig}），
 * 若这里再注册一个同类型 Bean 会污染 RAG 的自动装配、且两者维度/语义空间根本不同（文本 embedding
 * 与 CLIP 不可混用）。因此本接口的实现内部自管 HTTP 调用，<strong>不进 Spring 的 {@code EmbeddingModel}
 * 类型体系</strong>，与 {@code ai/vision} 不注册 ChatModel Bean 同思路。
 */
public interface MultimodalEmbeddingModel {

    /**
     * 把一段文本编码成跨模态向量（用于 image-search 的 query 侧）。
     *
     * @param text 查询文本
     * @return 向量（float[]），维度由后端模型决定
     */
    float[] embedText(String text);

    /**
     * 把一张图片编码成跨模态向量（用于图片入库侧）。<strong>必须与 {@link #embedText} 走同一个模型</strong>，
     * 否则两侧向量不在同一空间、相似度无意义。
     *
     * @param image    图片原始字节
     * @param mimeType MIME（如 {@code image/png}）
     * @return 向量（float[]），与 {@link #embedText} 同维、同空间
     */
    float[] embedImage(byte[] image, String mimeType);

    /**
     * 期望/标称维度（来自配置）。仅用于建库维度校验与日志；真实维度以返回向量长度为准。
     */
    int dimension();
}
