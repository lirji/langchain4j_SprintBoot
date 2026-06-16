package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code tasks/pushNotificationConfig/set} 的 params，也是该方法的 result。
 * 把一个 push 配置绑到某个 {@code taskId}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskPushNotificationConfig(String taskId, PushNotificationConfig pushNotificationConfig) {
}
