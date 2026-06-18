package com.lrj.langchain4j.voice;

/**
 * {@code app.voice.*} 绑定。默认关 → voice 相关 Bean 全不装配。
 * {@code base-url} 可指云 OpenAI / Azure / 本地 whisper+tts 网关——只要 OpenAI 兼容协议。
 */
public class VoiceProperties {

    private boolean enabled = false;
    /** 目前仅 {@code openai}（兼容协议）。接别家在 SpeechService 加实现 + 这里加分支。 */
    private String provider = "openai";
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    /** ASR 模型，如 {@code whisper-1} / {@code gpt-4o-transcribe}。 */
    private String asrModel = "whisper-1";
    /** TTS 模型，如 {@code tts-1} / {@code gpt-4o-mini-tts}。 */
    private String ttsModel = "tts-1";
    /** TTS 音色。 */
    private String ttsVoice = "alloy";
    /** TTS 输出格式：mp3 | wav | opus | ...（决定回复 content-type）。 */
    private String ttsFormat = "mp3";
    /** ASR 语言提示（如 {@code zh}），留空自动检测。 */
    private String language = "";
    private int timeoutSeconds = 30;
    /** SSE 半流式：一句的最小字数，低于此不单独切句 TTS（防过短句听感碎、省调用）。 */
    private int streamSentenceMinChars = 8;
    /** 上传音频字节上限，挡超大文件。默认 25MB。 */
    private long maxAudioBytes = 26214400L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getAsrModel() { return asrModel; }
    public void setAsrModel(String asrModel) { this.asrModel = asrModel; }
    public String getTtsModel() { return ttsModel; }
    public void setTtsModel(String ttsModel) { this.ttsModel = ttsModel; }
    public String getTtsVoice() { return ttsVoice; }
    public void setTtsVoice(String ttsVoice) { this.ttsVoice = ttsVoice; }
    public String getTtsFormat() { return ttsFormat; }
    public void setTtsFormat(String ttsFormat) { this.ttsFormat = ttsFormat; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getStreamSentenceMinChars() { return streamSentenceMinChars; }
    public void setStreamSentenceMinChars(int streamSentenceMinChars) { this.streamSentenceMinChars = streamSentenceMinChars; }
    public long getMaxAudioBytes() { return maxAudioBytes; }
    public void setMaxAudioBytes(long maxAudioBytes) { this.maxAudioBytes = maxAudioBytes; }

    /** TTS 格式 → HTTP content-type。 */
    public String ttsContentType() {
        return switch (ttsFormat == null ? "mp3" : ttsFormat.toLowerCase()) {
            case "wav" -> "audio/wav";
            case "opus" -> "audio/opus";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "pcm" -> "audio/pcm";
            default -> "audio/mpeg";
        };
    }
}
