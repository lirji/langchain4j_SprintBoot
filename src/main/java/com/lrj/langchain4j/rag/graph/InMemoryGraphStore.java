package com.lrj.langchain4j.rag.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存邻接表实现：{@code byEntity} 把归一后的实体名映射到「碰到它的所有三元组」（无向，
 * subject 和 object 都建索引）。遍历是 BFS，hop 近的三元组先收。
 *
 * <p>重启即丢、单 JVM、{@code removeWhere} 重建索引是 O(N) —— 跟 {@code DocumentMirror} 一样
 * 是脚手架级实现，超大图请换 Neo4j（{@code store=neo4j}，按信号补）。
 */
public class InMemoryGraphStore implements GraphStore {

    private final List<Triple> all = new CopyOnWriteArrayList<>();
    private final Map<String, List<Triple>> byEntity = new ConcurrentHashMap<>();

    @Override
    public void add(List<Triple> triples) {
        if (triples == null) return;
        for (Triple t : triples) {
            if (isBlank(t.subject()) || isBlank(t.object())) continue;
            all.add(t);
            index(t);
        }
    }

    private void index(Triple t) {
        byEntity.computeIfAbsent(norm(t.subject()), k -> new CopyOnWriteArrayList<>()).add(t);
        byEntity.computeIfAbsent(norm(t.object()), k -> new CopyOnWriteArrayList<>()).add(t);
    }

    @Override
    public List<Triple> neighbors(Set<String> seedSurfaces, int maxHops, String tenant, String category) {
        if (seedSurfaces == null || seedSurfaces.isEmpty() || maxHops < 1) return List.of();

        Set<String> visited = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        for (String s : seedSurfaces) {
            String n = norm(s);
            if (!n.isEmpty() && visited.add(n)) frontier.add(n);
        }

        // LinkedHashSet 保 hop 顺序（先收到的更靠近种子 = 更相关）+ 去重
        LinkedHashSet<Triple> collected = new LinkedHashSet<>();
        for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
            Set<String> next = new HashSet<>();
            for (String entity : frontier) {
                for (Triple t : byEntity.getOrDefault(entity, List.of())) {
                    if (!scopeMatches(t, tenant, category)) continue;
                    collected.add(t);
                    String other = norm(otherEndpoint(t, entity));
                    if (!other.isEmpty() && visited.add(other)) {
                        next.add(other);
                    }
                }
            }
            frontier = next;
        }
        return new ArrayList<>(collected);
    }

    @Override
    public Set<String> entities(String tenant, String category) {
        Set<String> out = new HashSet<>();
        for (Triple t : all) {
            if (scopeMatches(t, tenant, category)) {
                out.add(t.subject());
                out.add(t.object());
            }
        }
        return out;
    }

    @Override
    public int removeBySourcePrefix(String tenant, String sourceIdPrefix) {
        int before = all.size();
        all.removeIf(t -> Objects.equals(tenant, t.tenantId())
                && t.sourceId() != null && t.sourceId().startsWith(sourceIdPrefix));
        int removed = before - all.size();
        if (removed > 0) {
            byEntity.clear();
            for (Triple t : all) index(t);
        }
        return removed;
    }

    @Override
    public int size() {
        return all.size();
    }

    private static String otherEndpoint(Triple t, String normEntity) {
        return norm(t.subject()).equals(normEntity) ? t.object() : t.subject();
    }

    private static boolean scopeMatches(Triple t, String tenant, String category) {
        if (!Objects.equals(tenant, t.tenantId())) return false;
        if (category == null) return true;
        return Objects.equals(category, t.category());
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
