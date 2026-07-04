package com.lrj.langchain4j.rag.multimodal;

/**
 * {@code app.rag.multimodal-embedding.*} 绑定。<strong>默认关</strong> —— 关闭时整条原生多模态
 * embedding 链（{@link MultimodalEmbeddingConfig} / {@link MultimodalRetrievalService} /
 * 控制器）都不装配，零开销、零依赖网络。
 *
 * <p>这是「原生多模态向量」路径：把<strong>图片本身</strong>直接 embed 进跨模态向量空间（CLIP /
 * jina-clip），实现「文本 query ↔ 图片」互检索。区别于 {@code ai/vision} 的 caption→text 路径
 * （图先转文字描述、再走普通文本 embedding）—— 后者丢失了图内不可言说的视觉信息，前者保留。
 *
 * <p>跟主 chat（{@code app.llm.provider}）与文本 embedding（{@code app.embedding.provider}）
 * <strong>三向解耦</strong>：多模态 embedding 端点单独指向一个 CLIP / jina-clip 服务
 * （vLLM / TEI / 云 jina），走 OpenAI 兼容的 {@code /embeddings} 协议。刻意<strong>不注册成
 * {@code EmbeddingModel} Bean</strong>——否则会跟主文本 {@code EmbeddingModel} 撞类型、扰乱 RAG
 * 自动装配（与 {@code ai/vision} 不注册 ChatModel Bean 同思路），只暴露自定义
 * {@link MultimodalEmbeddingModel} 接口。
 */
public class MultimodalEmbeddingProperties {

    /** 总开关。关闭（默认）时整条多模态 embedding 链不装配。 */
    private boolean enabled = false;

    /** OpenAI 兼容 embedding 端点根，会拼 {@code /embeddings}。指向 vLLM/TEI/云 jina。 */
    private String baseUrl = "http://localhost:8000/v1";

    /** API key。本地 vLLM/TEI 通常不校验，留 EMPTY 即可；云 jina 填真实 key。 */
    private String apiKey = "";

    /** 多模态 embedding 模型名，如 {@code jinaai/jina-clip-v2} / {@code openai/clip-vit-base-patch32}。 */
    private String modelName = "jinaai/jina-clip-v2";

    /**
     * 期望向量维度（如 CLIP 512 / jina-clip-v2 1024）。>0 时对返回向量做长度校验，不一致打 WARN。
     * 也是接入方给持久化向量库（pgvector/milvus）建 image 集合时的维度依据。0 = 不校验。
     */
    private int dimension = 1024;

    private int timeoutSeconds = 60;

    /** 失败自动重试次数（429 / 5xx / 超时），本实现用 JDK HttpClient 手动重试。 */
    private int maxRetries = 2;

    /**
     * 图片以何种形式塞进 {@code input} 数组：
     * <ul>
     *   <li>{@code data-uri}（默认）— {@code {"image":"data:image/png;base64,..."}}，jina-clip 兼容</li>
     *   <li>{@code base64} — {@code {"image":"<纯 base64>"}}，部分 TEI 部署接受</li>
     * </ul>
     * 不同后端对多模态 input 约定不一，用这个开关适配而不改代码。
     */
    private String imageInputFormat = "data-uri";

    /** 单张图片字节上限，挡超大文件 OOM。默认 10MB。 */
    private long maxImageBytes = 10_485_760L;

    /** image-search 默认返回条数。 */
    private int topK = 5;

    /** image-search 默认最小余弦相似度阈值，低于此的命中丢弃。 */
    private double minScore = 0.0;

    private boolean logRequests = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public String getImageInputFormat() { return imageInputFormat; }
    public void setImageInputFormat(String imageInputFormat) { this.imageInputFormat = imageInputFormat; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }
    public boolean isLogRequests() { return logRequests; }
    public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
}
