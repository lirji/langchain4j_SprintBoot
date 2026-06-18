package com.lrj.langchain4j.ai.vision;

/**
 * {@code app.vision.*} 绑定。<strong>默认关</strong> → vision 相关 Bean 全不装配
 * （{@code VisionConfig} / {@code VisionController} 都条件化在 {@code app.vision.enabled=true}）。
 *
 * <p>跟主 chat provider（{@code app.llm.provider}）与 embedding（{@code app.embedding.provider}）
 * <strong>完全解耦</strong>——视觉模型可以单独指向 gpt-4o，而 chat 仍走本地 Ollama。这与 embedding
 * 解耦同思路：视觉理解与文本对话/向量化是三件独立的事，各自选最合适的后端。
 *
 * <p>{@code base-url} 可指云 OpenAI / Azure / 本地 vLLM/Ollama 网关——
 * provider=openai-compat 时只要 OpenAI 兼容协议即可（gpt-4o / qwen2.5-vl 等）；
 * provider=ollama 时走本地多模态模型（llava / qwen2.5-vl / llama3.2-vision）。
 */
public class VisionProperties {

    /** 总开关。关闭（默认）时整个 vision 链不装配。 */
    private boolean enabled = false;
    /** {@code openai-compat}（默认，云 OpenAI / Azure / vLLM）| {@code ollama}（本地多模态模型）。 */
    private String provider = "openai-compat";
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    /** 视觉模型名。openai-compat: {@code gpt-4o} / {@code gpt-4o-mini}；ollama: {@code llava} / {@code qwen2.5-vl}。 */
    private String modelName = "gpt-4o-mini";
    /** temperature。看图转写/描述偏确定性任务，默认压低到 0.2 减少漂移。 */
    private Double temperature = 0.2;
    private int timeoutSeconds = 60;
    /** 失败自动重试（429 / 5xx / 超时）。 */
    private int maxRetries = 3;
    /** 单张图片字节上限，挡超大文件 OOM / 超 token。默认 10MB。 */
    private long maxImageBytes = 10_485_760L;
    /**
     * caption 结果缓存条数（按图内容 SHA-256 去重）。同一张图重复上传 / 多次入库不再重复烧
     * 视觉调用。0 = 关闭缓存。只缓存 {@link #captionPrompt} 入库路径，不缓存 /chat/vision 的随问随答。
     */
    private int captionCacheSize = 256;
    private boolean logRequests = false;
    private boolean logResponses = false;

    /**
     * 入库/OCR 用的指令：既描述图像语义、又转写其中可见文字，<strong>一次调用同时覆盖
     * 「图像理解」与「OCR 转写」</strong>，产出的纯文本再走现有 chunk→embed→检索→引用全链。
     */
    private String captionPrompt = """
            你是文档理解助手。请用中文详尽描述这张图片，并满足：
            1) 概述图像主题与可见的关键对象/场景；
            2) 如果是图表（柱状/折线/饼图/表格等），说明它表达的数据趋势与关键数值；
            3) 逐字转写图中所有可见文字（OCR），保留原文语言，不要翻译；
            4) 只陈述图中真实可见的内容，不要臆测或补充图外信息。
            直接输出描述正文，不要寒暄、不要加「这张图片显示」之外的多余前缀。""";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
    public int getCaptionCacheSize() { return captionCacheSize; }
    public void setCaptionCacheSize(int captionCacheSize) { this.captionCacheSize = captionCacheSize; }
    public boolean isLogRequests() { return logRequests; }
    public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
    public boolean isLogResponses() { return logResponses; }
    public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    public String getCaptionPrompt() { return captionPrompt; }
    public void setCaptionPrompt(String captionPrompt) { this.captionPrompt = captionPrompt; }
}
