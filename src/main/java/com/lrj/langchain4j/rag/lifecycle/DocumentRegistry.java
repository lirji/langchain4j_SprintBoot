package com.lrj.langchain4j.rag.lifecycle;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-tenant 内存注册表：{@code tenantId -> docId -> DocumentInfo}。
 * 跟 {@link com.lrj.langchain4j.security.TokenBudgetTracker} 同款架构 —— 多实例切 Redis
 * 时把 {@link #map} 换成 redis hash，调用接口不动。
 */
@Component
public class DocumentRegistry {

    private final ConcurrentMap<String, ConcurrentMap<String, DocumentInfo>> map = new ConcurrentHashMap<>();

    public void put(DocumentInfo info) {
        map.computeIfAbsent(info.tenantId(), k -> new ConcurrentHashMap<>())
                .put(info.docId(), info);
    }

    public Optional<DocumentInfo> get(String tenantId, String docId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return Optional.empty();
        return Optional.ofNullable(tenantMap.get(docId));
    }

    public List<DocumentInfo> list(String tenantId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return List.of();
        return List.copyOf(tenantMap.values());
    }

    public Optional<DocumentInfo> remove(String tenantId, String docId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return Optional.empty();
        return Optional.ofNullable(tenantMap.remove(docId));
    }

    /** 仅给 actuator / debug 用 —— 返回整个 map 的浅拷贝。 */
    public Map<String, Collection<DocumentInfo>> snapshotAll() {
        Map<String, Collection<DocumentInfo>> out = new java.util.LinkedHashMap<>();
        map.forEach((tenant, docs) -> out.put(tenant, List.copyOf(docs.values())));
        return out;
    }
}
