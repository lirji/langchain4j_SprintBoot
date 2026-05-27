package com.lrj.langchain4j.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 task 存储：{@code Map<taskId, AsyncTask>}（任务全局唯一 id，per-tenant 隔离在
 * {@link AsyncTaskService} 查询时校验）。重启即丢；多实例切 Redis 时把 map 换成
 * {@code RedisTemplate.opsForValue()} 即可，调用接口保持不变。
 *
 * <p>带 TTL：每分钟扫一遍，把 finishedAt 超过 {@code app.async.task-ttl}（默认 24h）的 task
 * 清掉。避免长跑实例 map 无限增长。
 */
@Component
public class TaskStore {

    private static final Logger log = LoggerFactory.getLogger(TaskStore.class);

    private final ConcurrentMap<String, AsyncTask> tasks = new ConcurrentHashMap<>();
    private final Duration ttl;

    public TaskStore(@Value("${app.async.task-ttl:PT24H}") Duration ttl) {
        this.ttl = ttl;
    }

    public void put(AsyncTask task) {
        tasks.put(task.taskId(), task);
    }

    public Optional<AsyncTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** Atomically update 一个 task —— CAS 保证并发更新不打架（取消 vs 完成）。 */
    public Optional<AsyncTask> update(String taskId, java.util.function.UnaryOperator<AsyncTask> updater) {
        AsyncTask updated = tasks.computeIfPresent(taskId, (k, v) -> updater.apply(v));
        return Optional.ofNullable(updated);
    }

    public List<AsyncTask> listByTenant(String tenantId) {
        return tasks.values().stream()
                .filter(t -> tenantId.equals(t.tenantId()))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /** 每分钟扫一次，清掉 finishedAt 超过 TTL 的 task。{@code @Scheduled} 需要主类 @EnableScheduling。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cleanup() {
        Instant cutoff = Instant.now().minus(ttl);
        int removed = 0;
        for (var entry : tasks.entrySet()) {
            AsyncTask t = entry.getValue();
            if (t.finishedAt() != null && t.finishedAt().isBefore(cutoff)) {
                if (tasks.remove(entry.getKey(), t)) removed++;
            }
        }
        if (removed > 0) log.info("task-store cleanup: removed {} expired tasks (ttl={})", removed, ttl);
    }
}
