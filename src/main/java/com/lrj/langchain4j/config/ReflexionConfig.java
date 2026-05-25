package com.lrj.langchain4j.config;

import com.lrj.langchain4j.ai.reflexion.Answerer;
import com.lrj.langchain4j.ai.reflexion.Critic;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReflexionConfig {

    @Bean
    public Answerer answerer(ChatModel chatModel) {
        return AiServices.builder(Answerer.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    public Critic critic(ChatModel chatModel) {
        return AiServices.builder(Critic.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.reflexion")
    public ReflexionProperties reflexionProperties() {
        return new ReflexionProperties();
    }

    public static class ReflexionProperties {
        private double threshold = 0.75;
        private int maxAttempts = 2;
        private Weights weights = new Weights();

        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Weights getWeights() { return weights; }
        public void setWeights(Weights weights) { this.weights = weights; }

        /**
         * 加权聚合 Critique 三个维度的权重。默认正确性和完整性各占 40%、清晰度 20%
         * —— 优先不出错、其次回答全面，最后才追求表达。
         * 不需要归一化，{@code ReflexiveService} 内部按总和分母处理。
         */
        public static class Weights {
            private double correctness = 0.4;
            private double completeness = 0.4;
            private double clarity = 0.2;

            public double getCorrectness() { return correctness; }
            public void setCorrectness(double correctness) { this.correctness = correctness; }
            public double getCompleteness() { return completeness; }
            public void setCompleteness(double completeness) { this.completeness = completeness; }
            public double getClarity() { return clarity; }
            public void setClarity(double clarity) { this.clarity = clarity; }
        }
    }
}
