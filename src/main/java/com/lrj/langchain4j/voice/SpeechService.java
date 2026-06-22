package com.lrj.langchain4j.voice;

/**
 * 语音能力抽象：ASR（语音→文字）+ TTS（文字→语音）。把具体 provider（OpenAI 兼容 / Azure /
 * 本地 whisper+tts）挡在接口后，{@link VoiceConversationService} 不关心怎么实现——跟项目
 * chat/embedding 用 provider 开关解耦是同一思路。
 */
public interface SpeechService {

    /** ASR：音频字节 → 文字。{@code filename} 用于让 provider 嗅探音频格式（扩展名）。 */
    String transcribe(byte[] audio, String filename);

    /** TTS：文字 → 合成语音（含 content-type，如 {@code audio/mpeg}）。 */
    Speech synthesize(String text);

    /** 合成结果。 */
    record Speech(byte[] audio, String contentType) {}
}
