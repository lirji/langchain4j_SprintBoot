package com.lrj.langchain4j.rag.graph;

import java.util.List;
import java.util.Set;

/**
 * 知识图谱存储。两个实现：{@link InMemoryGraphStore}（默认，零依赖、重启即丢，跟 {@code DocumentMirror}/
 * {@code InMemoryEmbeddingStore} 一致）和 {@link JdbcGraphStore}（{@code store=jdbc}，MySQL 边表持久化，
 * 重启不丢）。Neo4j 分支仍可后补——接口对两者都够用。
 */
public interface GraphStore {

    /** 批量加边。subject/object 为空的三元组被跳过。 */
    void add(List<Triple> triples);

    /**
     * 从种子实体（表面形式）出发做 {@code maxHops} 跳广度遍历，返回连通的三元组（hop 近的在前）。
     * 在遍历内部就按 {@code tenant}/{@code category} 过滤 —— 跨租户的边绝不被遍历到（防路径泄漏）。
     *
     * @param category {@code null} 表示不限类别（匹配全部）；非 null 时要求三元组 category 相等
     */
    List<Triple> neighbors(Set<String> seedSurfaces, int maxHops, String tenant, String category);

    /** 当前 (tenant, category) 作用域下的全部实体表面形式 —— 给 token 实体链接做候选集。 */
    Set<String> entities(String tenant, String category);

    /**
     * 删某租户下 {@code sourceId} 以 {@code prefix} 开头的全部边（配合文档生命周期：delete/re-upload
     * 时按 {@code "<displayName>#"} 前缀清旧边）。返回删除数。JDBC 实现走 {@code WHERE source_id LIKE ?}。
     */
    int removeBySourcePrefix(String tenant, String sourceIdPrefix);

    /** 三元组总数（监控/调试用）。 */
    int size();
}
