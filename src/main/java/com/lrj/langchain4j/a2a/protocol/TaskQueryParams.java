package com.lrj.langchain4j.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * {@code tasks/get} / {@code tasks/cancel} / {@code tasks/pushNotificationConfig/get} 的 params ——
 * 都只需要一个 task {@code id}（外加可选 historyLength / metadata，MVP 忽略）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskQueryParams(String id, Integer historyLength, Map<String, Object> metadata) {
}
