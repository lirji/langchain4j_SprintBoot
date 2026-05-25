package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.extract.Extractor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExtractorConfig {

    @Bean
    public Extractor extractor(ChatModel chatModel) {
        return AiServices.builder(Extractor.class)
                .chatModel(chatModel)
                .build();
    }
}
