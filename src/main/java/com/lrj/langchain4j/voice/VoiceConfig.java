package com.lrj.langchain4j.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.langchain4j.channel.CustomerServiceBrain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语音客服装配。<strong>整个 config 条件化在 {@code app.voice.enabled=true}</strong> ——
 * 关闭（默认）时 voice 相关 Bean 全不存在（{@code /voice/**} 端点的服务缺失，controller 软依赖见
 * {@code VoiceController}）。SpeechService 按 provider 选实现，目前仅 openai 兼容。
 */
@Configuration
@ConditionalOnProperty(name = "app.voice.enabled", havingValue = "true")
public class VoiceConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.voice")
    public VoiceProperties voiceProperties() {
        return new VoiceProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "app.voice.provider", havingValue = "openai", matchIfMissing = true)
    public SpeechService openAiSpeechService(VoiceProperties props, ObjectMapper mapper) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            // 不硬失败（本地网关可能不校验 key），但提示——云 OpenAI 缺 key 会在首个请求 401
            // 留空时多半是忘配 OPENAI_API_KEY
            org.slf4j.LoggerFactory.getLogger(VoiceConfig.class)
                    .warn("app.voice.api-key is blank; cloud OpenAI will 401. Set OPENAI_API_KEY if not using a local gateway.");
        }
        return new OpenAiSpeechService(props, mapper);
    }

    @Bean
    public VoiceConversationService voiceConversationService(SpeechService speechService,
                                                             CustomerServiceBrain brain) {
        return new VoiceConversationService(speechService, brain);
    }

    @Bean
    public VoiceStreamService voiceStreamService(com.lrj.langchain4j.ai.Assistant assistant,
                                                 com.lrj.langchain4j.config.ResolvedAssistantStyle style,
                                                 SpeechService speechService,
                                                 VoiceProperties props) {
        return new VoiceStreamService(assistant, style, speechService, props.getStreamSentenceMinChars());
    }
}

