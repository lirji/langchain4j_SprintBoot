package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A push notification 配置：终态时 server 往 {@code url} POST 任务结果。{@code token} 由客户端
 * 提供，server 回推时原样带回（放 header），供客户端校验回调真伪。MVP 不做 nested authentication。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PushNotificationConfig(String url, String token, String id) {
}
