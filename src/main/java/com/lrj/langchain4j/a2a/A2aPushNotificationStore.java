package com.lrj.langchain4j.a2a;

import com.lrj.langchain4j.a2a.protocol.PushNotificationConfig;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A2A push 配置存储：{@code taskId -> PushNotificationConfig}。**刻意跟 {@code AsyncTask.webhookUrl}
 * 分开**：A2A 任务不写 {@code AsyncTask.webhookUrl}，所以现有 {@code WebhookDispatcher} 跳过它们，
 * 由 {@link A2aPushNotifier} 按 A2A payload 格式独立回推，两条 webhook 通道互不重复触发。
 *
 * <p>进程内 map，重启即丢；多实例换 Redis（key {@code a2a:push:<taskId>}），调用接口不变 ——
 * 跟 {@code TaskStore} / {@code TokenBudgetTracker} 同款演进路径。
 */
@Component
public class A2aPushNotificationStore {

    private final ConcurrentMap<String, PushNotificationConfig> configs = new ConcurrentHashMap<>();

    public void put(String taskId, PushNotificationConfig config) {
        configs.put(taskId, config);
    }

    public Optional<PushNotificationConfig> get(String taskId) {
        return Optional.ofNullable(configs.get(taskId));
    }

    public void remove(String taskId) {
        configs.remove(taskId);
    }
}
