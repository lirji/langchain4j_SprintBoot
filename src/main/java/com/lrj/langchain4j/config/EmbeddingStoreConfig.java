package com.lrj.langchain4j.config;

import com.lrj.langchain4j.store.doris.DorisEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore.SearchMode;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreConfig.class);

    // ---------------------------------------------------------------- in-memory

    @Bean
    @ConditionalOnProperty(name = "app.rag.store", havingValue = "in-memory", matchIfMissing = true)
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        log.info("Using InMemoryEmbeddingStore (volatile — data lost on restart)");
        return new InMemoryEmbeddingStore<>();
    }

    // ---------------------------------------------------------------- pgvector

    @Bean
    @ConditionalOnProperty(name = "app.rag.store", havingValue = "pgvector")
    public EmbeddingStore<TextSegment> pgVectorEmbeddingStore(EmbeddingModel embeddingModel,
                                                              PgVectorProperties props) {
        SearchMode mode = SearchMode.valueOf(props.getSearchMode());
        log.info("Using PgVectorEmbeddingStore at {}:{}/{} table={} dim={} mode={}",
                props.getHost(), props.getPort(), props.getDatabase(), props.getTable(),
                embeddingModel.dimension(), mode);
        return PgVectorEmbeddingStore.builder()
                .host(props.getHost())
                .port(props.getPort())
                .database(props.getDatabase())
                .user(props.getUser())
                .password(props.getPassword())
                .table(props.getTable())
                .dimension(embeddingModel.dimension())
                .createTable(props.isCreateTable())
                .useIndex(props.isUseIndex())
                .indexListSize(props.getIndexListSize())
                .searchMode(mode)
                .textSearchConfig(props.getTextSearchConfig())
                .rrfK(props.getRrfK())
                .build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.rag.pgvector")
    public PgVectorProperties pgVectorProperties() {
        return new PgVectorProperties();
    }

    // ---------------------------------------------------------------- milvus

    @Bean
    @ConditionalOnProperty(name = "app.rag.store", havingValue = "milvus")
    public EmbeddingStore<TextSegment> milvusEmbeddingStore(EmbeddingModel embeddingModel,
                                                            MilvusProperties props) {
        log.info("Using MilvusEmbeddingStore at {}:{} collection={} dim={}",
                props.getHost(), props.getPort(), props.getCollection(), embeddingModel.dimension());
        MilvusEmbeddingStore.Builder b = MilvusEmbeddingStore.builder()
                .host(props.getHost())
                .port(props.getPort())
                .collectionName(props.getCollection())
                .dimension(embeddingModel.dimension())
                .indexType(IndexType.valueOf(props.getIndexType()))
                .metricType(MetricType.valueOf(props.getMetricType()))
                .consistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .autoFlushOnInsert(true);
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            b.username(props.getUsername());
        }
        if (props.getPassword() != null && !props.getPassword().isBlank()) {
            b.password(props.getPassword());
        }
        return b.build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.rag.milvus")
    public MilvusProperties milvusProperties() {
        return new MilvusProperties();
    }

    // ---------------------------------------------------------------- chroma

    @Bean
    @ConditionalOnProperty(name = "app.rag.store", havingValue = "chroma")
    public EmbeddingStore<TextSegment> chromaEmbeddingStore(ChromaProperties props) {
        log.info("Using ChromaEmbeddingStore at {} collection={}", props.getBaseUrl(), props.getCollection());
        ChromaEmbeddingStore.Builder b = ChromaEmbeddingStore.builder()
                .baseUrl(props.getBaseUrl())
                .collectionName(props.getCollection());
        if (props.getTenant() != null && !props.getTenant().isBlank()) {
            b.tenantName(props.getTenant());
        }
        if (props.getDatabase() != null && !props.getDatabase().isBlank()) {
            b.databaseName(props.getDatabase());
        }
        return b.build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.rag.chroma")
    public ChromaProperties chromaProperties() {
        return new ChromaProperties();
    }

    // ---------------------------------------------------------------- qdrant

    @Bean
    @ConditionalOnProperty(name = "app.rag.store", havingValue = "qdrant")
    public EmbeddingStore<TextSegment> qdrantEmbeddingStore(QdrantProperties props) {
        log.info("Using QdrantEmbeddingStore at {}:{} collection={}",
                props.getHost(), props.getPort(), props.getCollection());
        QdrantEmbeddingStore.Builder b = QdrantEmbeddingStore.builder()
                .host(props.getHost())
                .port(props.getPort())
                .collectionName(props.getCollection())
                .useTls(props.isUseTls());
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
            b.apiKey(props.getApiKey());
        }
        return b.build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.rag.qdrant")
    public QdrantProperties qdrantProperties() {
        return new QdrantProperties();
    }

    // ---------------------------------------------------------------- doris

    @Bean
    @ConditionalOnProperty(name = "app.rag.store", havingValue = "doris")
    public EmbeddingStore<TextSegment> dorisEmbeddingStore(EmbeddingModel embeddingModel,
                                                           DorisProperties props) {
        log.info("Using DorisEmbeddingStore at {} table={} dim={} metric={}",
                props.getJdbcUrl(), props.getTable(), embeddingModel.dimension(), props.getMetric());
        return new DorisEmbeddingStore(
                props.getJdbcUrl(),
                props.getUser(),
                props.getPassword(),
                props.getTable(),
                embeddingModel.dimension(),
                props.getMetric(),
                props.isCreateTable(),
                props.getBuckets()
        );
    }

    @Bean
    @ConfigurationProperties(prefix = "app.rag.doris")
    public DorisProperties dorisProperties() {
        return new DorisProperties();
    }

    // ---------------------------------------------------------------- properties

    public static class PgVectorProperties {
        private String host = "localhost";
        private int port = 5432;
        private String database = "postgres";
        private String user = "postgres";
        private String password = "postgres";
        private String table = "document_embeddings";
        private boolean createTable = true;
        private boolean useIndex = true;
        private int indexListSize = 100;
        private String searchMode = "VECTOR";
        private String textSearchConfig = "simple";
        private int rrfK = 60;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public boolean isCreateTable() { return createTable; }
        public void setCreateTable(boolean createTable) { this.createTable = createTable; }
        public boolean isUseIndex() { return useIndex; }
        public void setUseIndex(boolean useIndex) { this.useIndex = useIndex; }
        public int getIndexListSize() { return indexListSize; }
        public void setIndexListSize(int indexListSize) { this.indexListSize = indexListSize; }
        public String getSearchMode() { return searchMode; }
        public void setSearchMode(String searchMode) { this.searchMode = searchMode; }
        public String getTextSearchConfig() { return textSearchConfig; }
        public void setTextSearchConfig(String textSearchConfig) { this.textSearchConfig = textSearchConfig; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
    }

    public static class MilvusProperties {
        private String host = "localhost";
        private int port = 19530;
        private String collection = "document_embeddings";
        private String indexType = "FLAT";
        private String metricType = "COSINE";
        private String username;
        private String password;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public String getIndexType() { return indexType; }
        public void setIndexType(String indexType) { this.indexType = indexType; }
        public String getMetricType() { return metricType; }
        public void setMetricType(String metricType) { this.metricType = metricType; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class ChromaProperties {
        private String baseUrl = "http://localhost:8000";
        private String collection = "document_embeddings";
        private String tenant;
        private String database;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public String getTenant() { return tenant; }
        public void setTenant(String tenant) { this.tenant = tenant; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
    }

    public static class QdrantProperties {
        private String host = "localhost";
        private int port = 6334;
        private String collection = "document_embeddings";
        private String apiKey;
        private boolean useTls = false;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public boolean isUseTls() { return useTls; }
        public void setUseTls(boolean useTls) { this.useTls = useTls; }
    }

    public static class DorisProperties {
        private String jdbcUrl = "jdbc:mysql://localhost:9030/demo";
        private String user = "root";
        private String password = "";
        private String table = "document_embeddings";
        private String metric = "cosine";
        private boolean createTable = true;
        private int buckets = 4;

        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public boolean isCreateTable() { return createTable; }
        public void setCreateTable(boolean createTable) { this.createTable = createTable; }
        public int getBuckets() { return buckets; }
        public void setBuckets(int buckets) { this.buckets = buckets; }
    }
}
