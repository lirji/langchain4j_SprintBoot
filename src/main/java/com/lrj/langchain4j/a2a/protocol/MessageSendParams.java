package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * {@code message/send} 与 {@code message/stream} 的 params。
 * {@code configuration.pushNotificationConfig} 给异步任务登记 webhook。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageSendParams(A2aMessage message, Configuration configuration, Map<String, Object> metadata) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Configuration(PushNotificationConfig pushNotificationConfig,
                                Boolean blocking,
                                java.util.List<String> acceptedOutputModes) {
    }
}
