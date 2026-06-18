package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.Assistant;
import com.lrj.langchain4j.memory.profile.InMemoryUserProfileStore;
import com.lrj.langchain4j.memory.profile.ProfileExtractor;
import com.lrj.langchain4j.memory.profile.UserProfileChatService;
import com.lrj.langchain4j.memory.profile.UserProfileProperties;
import com.lrj.langchain4j.memory.profile.UserProfileService;
import com.lrj.langchain4j.memory.profile.UserProfileStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 长期记忆 / 用户画像装配。<strong>整个 config 条件化在 {@code app.memory.profile.enabled=true}</strong>，
 * 关闭（默认）时全不装配、对话链零变化。抽取器走 temp=0 判官模型（不注册 ChatModel Bean，避免冲突）。
 */
@Configuration
@ConditionalOnProperty(name = "app.memory.profile.enabled", havingValue = "true")
public class MemoryProfileConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.memory.profile")
    public UserProfileProperties userProfileProperties() {
        return new UserProfileProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.profile.store", havingValue = "in-memory", matchIfMissing = true)
    public UserProfileStore userProfileStore(UserProfileProperties props) {
        // store=redis 的持久化分支按信号补；接口已留在 UserProfileStore
        return new InMemoryUserProfileStore(props.getMaxItems());
    }

    @Bean
    public ProfileExtractor profileExtractor(LlmConfig llmConfig, LlmConfig.LlmProperties props) {
        ChatModel model = llmConfig.buildJudgeChatModel(props);   // temp=0，确定性抽取
        return AiServices.builder(ProfileExtractor.class).chatModel(model).build();
    }

    @Bean(name = "profileExecutor")
    public Executor profileExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(128);
        ex.setThreadNamePrefix("profile-observe-");
        ex.initialize();
        return ex;
    }

    @Bean
    public UserProfileService userProfileService(UserProfileStore store, ProfileExtractor extractor,
                                                 UserProfileProperties props,
                                                 @org.springframework.beans.factory.annotation.Qualifier("profileExecutor")
                                                 Executor profileExecutor) {
        return new UserProfileService(store, extractor, profileExecutor, props.isAsync(), props.getRecallLimit());
    }

    @Bean
    public UserProfileChatService userProfileChatService(Assistant assistant,
                                                         ResolvedAssistantStyle style,
                                                         UserProfileService profileService) {
        return new UserProfileChatService(assistant, style, profileService);
    }
}
