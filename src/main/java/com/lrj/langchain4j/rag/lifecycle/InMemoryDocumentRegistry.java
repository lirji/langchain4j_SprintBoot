package com.lrj.langchain4j.rag.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内存的 {@link DocumentRegistry}（默认）。重启即丢 —— 单实例 / 本地开发够用。
 * 多实例 / 重启需持久化时切 {@link RedisDocumentRegistry}（{@code app.rag.registry.store=redis}）。
 */
@Component
@ConditionalOnProperty(name = "app.rag.registry.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryDocumentRegistry implements DocumentRegistry {

    private final ConcurrentMap<String, ConcurrentMap<String, DocumentInfo>> map = new ConcurrentHashMap<>();

    @Override
    public void put(DocumentInfo info) {
        map.computeIfAbsent(info.tenantId(), k -> new ConcurrentHashMap<>())
                .put(info.docId(), info);
    }

    @Override
    public Optional<DocumentInfo> get(String tenantId, String docId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return Optional.empty();
        return Optional.ofNullable(tenantMap.get(docId));
    }

    @Override
    public List<DocumentInfo> list(String tenantId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return List.of();
        return List.copyOf(tenantMap.values());
    }

    @Override
    public Optional<DocumentInfo> remove(String tenantId, String docId) {
        ConcurrentMap<String, DocumentInfo> tenantMap = map.get(tenantId);
        if (tenantMap == null) return Optional.empty();
        return Optional.ofNullable(tenantMap.remove(docId));
    }

    @Override
    public Map<String, Collection<DocumentInfo>> snapshotAll() {
        Map<String, Collection<DocumentInfo>> out = new LinkedHashMap<>();
        map.forEach((tenant, docs) -> out.put(tenant, List.copyOf(docs.values())));
        return out;
    }
}
