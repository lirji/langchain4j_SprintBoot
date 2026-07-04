# 原生多模态（CLIP）Embedding

把**图片本身**直接编码进跨模态向量空间（CLIP / jina-clip），实现「文本 query ↔ 图片」互检索。这是对 `ai/vision`（`docs/multimodal.md`）的**正交补充**：

| 路径 | 做法 | 保留信息 | 适用 |
| --- | --- | --- | --- |
| `ai/vision`（caption→text） | 图先经视觉模型转成文字描述/OCR，再走**普通文本 embedding** 入现有 RAG 链 | 只保留「能说出来」的视觉信息，丢失构图/色彩/风格等不可言说特征；但可被现有文本 RAG 全链（chunk/引用/grounding）直接复用 | 图里主要是**文字/图表**、要沉淀进知识库文本问答 |
| `rag/multimodal`（本特性，native） | 图片**直接** embed 进 CLIP/jina-clip 跨模态向量空间；文本 query 用**同一模型**embed 后算相似度 | 保留图的原生视觉语义 | 以图搜图语义、**文本描述搜图**（"红色跑车"命中图片），图内容难以言说 |

**默认关**（`app.rag.multimodal-embedding.enabled=false`），零新依赖（JDK `HttpClient`，跟 `voice/OpenAiSpeechService` 同款手搓 HTTP），与主 chat / 文本 embedding **三向解耦**。

## 包结构（`src/main/java/com/lrj/langchain4j/rag/multimodal`）

| 文件 | 职责 |
| --- | --- |
| `MultimodalEmbeddingModel` | 抽象接口：`embedText(text)` / `embedImage(bytes,mime)` → `float[]`（同空间同维）+ `dimension()`。**刻意不暴露 `dev.langchain4j...EmbeddingModel`**——主 RAG 已装配一个文本 `EmbeddingModel`，再注册同类型 Bean 会污染自动装配，且两者维度/语义空间不可混用。与 `ai/vision` 不注册 ChatModel Bean 同思路 |
| `DefaultMultimodalEmbeddingModel` | 默认实现：走 OpenAI 兼容 `POST {base-url}/embeddings`，JDK `HttpClient` 手搓。文本以字符串元素进 `input`；图片以 `{"image":"data:<mime>;base64,..."}` 对象元素进 `input`（jina-clip 多模态约定，`image-input-format` 可切 `base64`）。真正发 HTTP 收敛到 `protected String post(String)` 一个方法，**单测覆盖它即脱网** |
| `MultimodalEmbeddingProperties` | `app.rag.multimodal-embedding.*` 绑定 |
| `MultimodalRetrievalService` | 入库钩子 `ingestImage(bytes,mime,fileName)` → 向量 → 存进**现有** `EmbeddingStore`，打 `type=image`/`file_name`/`tenantId`/`mime` metadata；`searchByText(query,topK,minScore)` → 文本 embed → 检索。检索强制 AND `tenantId`（多租户隔离，复用 `TenantContext`，同主 RAG `tenantScopedFilter`）+ `type=image`（维度安全，见下） |
| `MultimodalEmbeddingConfig` | `@ConditionalOnProperty(app.rag.multimodal-embedding.enabled=true)` 装配 model + retrieval service（复用主 `EmbeddingStore` Bean） |
| `ImageSearchController` | `POST /rag/image`（上传图片入库）+ `POST /rag/image-search`（文本搜图），同 `/chat` 鉴权链 |

## 维度安全（重要）

CLIP/jina-clip 的图向量维度（如 512/1024）通常**不同于**主 RAG 的文本 embedding（如 nomic-embed-text 768）。两类向量若混在同一物理库，用 CLIP 维度的 query 去和文本 chunk 向量算点积会维度不符报错。

`MultimodalRetrievalService.searchByText` 因此强制 AND 一个 `type=image` filter，让 `EmbeddingStore` **只在 image 向量之间**算相似度。这样：

- `InMemoryEmbeddingStore`（默认，本地开发）：进程内，重启即丢，混存无持久后果，`type=image` filter 即可正确隔离。单测 `search_filtersOutNonImageEntries` 覆盖了「文本 chunk 共库时被 filter 挡掉」。
- **持久化库（pgvector / milvus / …）**：强烈建议给 image 向量**单开一个维度匹配的集合/表**（如 pgvector 建一张 `image_embeddings` 维度=CLIP dim 的表），而不是塞进文本 chunk 的表——否则底层向量列维度冲突。当前实现复用主 `EmbeddingStore` Bean 便于开箱演示（in-memory / 单维度库场景），生产多维度共存需按此拆库（见「生产接入」）。

## 配置

`app.rag.multimodal-embedding.*`：

| key | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关，关闭时整条链不装配 |
| `base-url` | `http://localhost:8000/v1` | OpenAI 兼容 embedding 端点根，拼 `/embeddings`。指向 vLLM/TEI/云 jina |
| `api-key` | `""` | 本地 vLLM/TEI 通常不校验，留空发 `EMPTY`；云 jina 填真实 key |
| `model-name` | `jinaai/jina-clip-v2` | 多模态 embedding 模型名 |
| `dimension` | `1024` | 期望向量维度（CLIP 512 / jina-clip-v2 1024）。>0 时对返回向量长度校验不一致打 WARN；也是建 image 集合的维度依据。0=不校验 |
| `image-input-format` | `data-uri` | 图片承载：`data-uri`（`data:<mime>;base64,...`，jina-clip 兼容）\| `base64`（纯 base64，部分 TEI 部署接受） |
| `timeout-seconds` | `60` | |
| `max-retries` | `2` | 手动退避重试次数（429/5xx/超时） |
| `max-image-bytes` | `10485760` | 单图上限 10MB |
| `top-k` | `5` | image-search 默认返回条数 |
| `min-score` | `0.0` | image-search 默认最小余弦相似度 |
| `log-requests` | `false` | |

## 端点

两个端点都走 `/chat` 同款鉴权链（`X-Api-Key` + 多租户 + 限流 + 配额），落在 `anyRequest().authenticated()`。

### `POST /rag/image` — 图片入库

```bash
curl -X POST localhost:8080/rag/image \
  -H 'X-Api-Key: <key>' \
  -F 'image=@car.jpg'
# → {"id":"<store-id>","fileName":"car.jpg"}
```

### `POST /rag/image-search` — 文本搜图

```bash
curl -X POST localhost:8080/rag/image-search \
  -H 'X-Api-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"query":"红色的跑车","topK":5,"minScore":0.2}'
# → {"query":"红色的跑车","results":[{"id":"...","fileName":"car.jpg","score":0.83}, ...]}
```

## 怎么跑

1. 起一个 OpenAI 兼容多模态 embedding 端点，例如 vLLM 托管 jina-clip：

   ```bash
   vllm serve jinaai/jina-clip-v2 --task embed --port 8000
   # 或云 jina：base-url=https://api.jina.ai/v1, api-key=<jina key>, model-name=jina-clip-v2
   ```

2. 开开关启动：

   ```bash
   APP_RAG_MULTIMODAL_EMBEDDING_ENABLED=true \
   APP_RAG_MULTIMODAL_EMBEDDING_BASE_URL=http://localhost:8000/v1 \
   APP_RAG_MULTIMODAL_EMBEDDING_MODEL_NAME=jinaai/jina-clip-v2 \
   APP_RAG_MULTIMODAL_EMBEDDING_DIMENSION=1024 \
   mvn spring-boot:run
   ```

   （多 key 覆盖用 env var，别堆逗号——见 CLAUDE.md 注意事项。）

3. 上传几张图 `POST /rag/image`，再 `POST /rag/image-search` 用文本描述搜。

## 测试

`src/test/java/com/lrj/langchain4j/rag/multimodal/MultimodalEmbeddingTest.java`（确定性，不连网/不连模型）：

- `embedText_buildsInputAndParsesVector` — 桩掉 `post()`，验证文本请求体拼装（字符串元素 + model 字段）+ 响应 `data[0].embedding` 解析成期望维度 `float[]`
- `embedImage_buildsDataUriInput` — 图片以 `{"image":"data:image/png;base64,..."}` 对象元素进 `input`
- `embedImage_rejectsEmpty` — 空图抛 `IllegalArgumentException`
- `ingestImage_storesWithImageMetadata_andTextSearchHits` — 假模型（文本/图返回同向量）→ 图入库带 `type=image`/`file_name` metadata 存进 `InMemoryEmbeddingStore` → 文本 query 触发 search 命中该图（cosine≈1.0），`fileName=car.jpg`
- `search_filtersOutNonImageEntries` — 库里混塞一条非 image 文本向量，检索被 `type=image` filter 挡掉，只命中 image

## 未来项

- **持久化多维度共库**：给 image 向量单开维度匹配的集合/表（pgvector 独立表 / milvus 独立 collection），而非复用主 `EmbeddingStore` Bean
- **图搜图**（image→image，上传一张图找相似图）：`MultimodalRetrievalService` 加一个 `searchByImage(bytes,mime)` 走 `embedImage` 即可
- **生命周期同步**：图片删除时按 `file_name`/`tenantId` 前缀 `removeAll`（同 `rag/lifecycle` 套路）
- **caption + native 双写**：一张图同时走 caption→text（进文本 RAG）与 native（进 image 向量），召回时融合
