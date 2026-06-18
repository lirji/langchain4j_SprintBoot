package com.lrj.langchain4j.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code app.rag.graph.*} 绑定。默认关 → 行为与历史完全一致。
 * 两个最该调的旋钮是 {@code maxHops}（召回完整性 vs 噪声/成本）和 {@code maxTriples}（context 上限）。
 */
public class GraphRagProperties {

    /** 总开关。false（默认）时 graph 相关 Bean 全不装配，检索链零变化。 */
    private boolean enabled = false;

    /** 存储：{@code in-memory}（默认，零依赖、重启即丢）| {@code jdbc}（MySQL 边表持久化，重启不丢）。 */
    private String store = "in-memory";

    /** 遍历跳数。1 够大多数关系问题；2 召回更全但拉进弱相关实体、context 变脏，调前先跑 eval。 */
    private int maxHops = 1;

    /** 单次召回三元组上限，挡高连通实体把 context 撑爆。 */
    private int maxTriples = 30;

    /** 实体链接：{@code token}（默认，零 LLM）| {@code llm}（抽 query 实体再锚定，更准，每 query 多 1 次 LLM）。 */
    private String entityLinking = "token";

    /** 建图是否投后台异步（G3）。true 时 {@code ingest} 不阻塞入库请求；大语料建议开。 */
    private boolean async = false;

    /** 别名规范化表（G4 轻量实体消歧）：表面形式 → canonical，如 {@code 张三经理: 张三}。入库时套用。 */
    private Map<String, String> aliases = new HashMap<>();

    private Extract extract = new Extract();
    private Jdbc jdbc = new Jdbc();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public int getMaxHops() { return maxHops; }
    public void setMaxHops(int maxHops) { this.maxHops = maxHops; }
    public int getMaxTriples() { return maxTriples; }
    public void setMaxTriples(int maxTriples) { this.maxTriples = maxTriples; }
    public String getEntityLinking() { return entityLinking; }
    public void setEntityLinking(String entityLinking) { this.entityLinking = entityLinking; }
    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }
    public Map<String, String> getAliases() { return aliases; }
    public void setAliases(Map<String, String> aliases) { this.aliases = aliases; }
    public Extract getExtract() { return extract; }
    public void setExtract(Extract extract) { this.extract = extract; }
    public Jdbc getJdbc() { return jdbc; }
    public void setJdbc(Jdbc jdbc) { this.jdbc = jdbc; }

    public static class Extract {
        /** 单 chunk 抽取三元组上限，兜底防模型把每个名词都连成边。 */
        private int maxTriplesPerChunk = 12;

        /** 受限 schema（G4）：非空时只保留 relation 归一后在此白名单内的边，并把允许集喂进抽取 prompt。 */
        private List<String> relationTypes = new java.util.ArrayList<>();

        public int getMaxTriplesPerChunk() { return maxTriplesPerChunk; }
        public void setMaxTriplesPerChunk(int maxTriplesPerChunk) { this.maxTriplesPerChunk = maxTriplesPerChunk; }
        public List<String> getRelationTypes() { return relationTypes; }
        public void setRelationTypes(List<String> relationTypes) { this.relationTypes = relationTypes; }
    }

    /** {@code store=jdbc} 时用（G3 持久化，MySQL 边表）。 */
    public static class Jdbc {
        private String url = "jdbc:mysql://localhost:3306/graph?useUnicode=true&characterEncoding=utf8";
        private String username = "root";
        private String password = "";
        private String table = "graph_triple";
        private boolean createTable = true;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public boolean isCreateTable() { return createTable; }
        public void setCreateTable(boolean createTable) { this.createTable = createTable; }
    }
}
