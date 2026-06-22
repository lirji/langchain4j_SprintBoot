# 企业知识库问答系统：部署与使用

把这个仓库当「多租户企业知识库问答系统」落地的完整说明。后端能力（多租户 RAG、租户隔离检索、
文档 CRUD + 版本覆盖、引用闭环、事实幻觉校验、auth/限流/配额/审计）大部分早已具备；本文聚焦
**让它真能装企业文档、重启不丢、按租户隔离问答**所需的部署与验证。

> 关联：平台基线 → `production-hardening.md`；待完善项 → `roadmap.md`；项目导航 → `CLAUDE.md`。

## 这次落地补的两个硬缺口

1. **持久化向量库**：默认 `app.rag.store=in-memory` 重启即丢整个知识库 → 切 **Milvus**。
2. **PDF / Office 文档**：per-tenant 上传 `POST /rag/documents` 之前只收 `text/*`；现在走
   **Apache Tika**（`DocumentTextExtractor`）解析 PDF / Word / Excel / PPT / HTML / 纯文本等，
   按内容嗅探类型不靠后缀。（文件夹批量入库 `POST /rag/ingest` 本就经 easy-rag 的 Tika 解析，一直支持。）

两者 + Redis 持久化记忆 + grounding + multipart 放大，统一收进一个 **`kb` profile**（`application-kb.yml`）。

## 启动

```bash
# 1. 持久化依赖
docker run -d -p 19530:19530 milvusdb/milvus:v2.4.10 milvus run standalone
docker run -d -p 6379:6379 redis
# 2. 模型（本地零成本默认；生产建议换 vLLM + bge-m3，见 CLAUDE.md）
ollama pull llama3.1 && ollama pull nomic-embed-text
# 3. 以 kb profile 启动
mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=kb
```

`kb` profile 相对默认 `application.yml` 改了：`app.rag.store=milvus`、`app.memory.store=redis`、
`app.rag.grounding.enabled=true`、`spring.servlet.multipart.max-file-size=20MB`。可用环境变量覆盖
`MILVUS_HOST/MILVUS_PORT/REDIS_HOST/REDIS_PORT`。

## 鉴权与租户

`application.yml` 里 seed 了两个 demo key（生产换成 DB/Redis 后端）：

| X-Api-Key | tenant | scopes | 能否上传文档 |
| --- | --- | --- | --- |
| `dev-key-tenantA-admin` | tenantA | chat, ingest, eval | ✅（有 `ingest`） |
| `dev-key-tenantB-readonly` | tenantB | chat | ❌（缺 `ingest`），仅能问答 |

每个请求带 `X-Api-Key` → `TenantContext` 解析出 tenantId → 上传时写进 segment metadata、检索时被
`LangChain4jConfig.tenantScopedFilter` 强制 AND 进 filter。**租户隔离是检索层兜底，不是应用层 if 判断。**

## 端到端验证

```bash
A='X-Api-Key: dev-key-tenantA-admin'
B='X-Api-Key: dev-key-tenantB-readonly'

# 1) 上传 PDF（租户 A）。displayName 取原始文件名，category 可选
curl -X POST localhost:8080/rag/documents -H "$A" \
  -F 'file=@handbook.pdf' -F 'category=manual'
# → 200 {"docId":"...", "segments": N>0, "version":1, ...}

# 2) 列出 + 提问（带来源引用 + grounding 提示）
curl localhost:8080/rag/documents -H "$A"
curl -X POST 'localhost:8080/chat?chatId=u1' -H "$A" \
  -H 'Content-Type: application/json' -d '{"message":"<问 handbook.pdf 里的内容>"}'
# → reply 含 [doc=handbook.pdf#N] 引用；疑似不被支撑时末尾追加 ⚠️ 可信度提示

# 3) 多租户隔离：租户 B 看不到、也问不到 A 的文档
curl localhost:8080/rag/documents -H "$B"                 # → []
curl -X POST 'localhost:8080/chat?chatId=u1' -H "$B" \
  -H 'Content-Type: application/json' -d '{"message":"<同一个问题>"}'
# → 弃答 / 答不出（检索被 tenantId=tenantB 过滤，召回不到 A 的片段）

# 4) 版本覆盖：改 handbook.pdf 内容重传 → 同名覆盖 version+1 → 提问只召回新内容
curl -X POST localhost:8080/rag/documents -H "$A" -F 'file=@handbook.pdf'
#   ⚠️ 见下「Milvus 删除」——确认旧版本片段不再被召回

# 5) 持久化：重启 app（不动 Milvus 容器）→ 重跑第 2 步仍答得出 → 证明重启不丢库
```

通过标准：PDF 能问答、引用格式正确、租户 B 隔离生效、重启后 KB 仍在、重复上传不召回旧片段。

## 注意：Milvus 下的文档删除 / 版本覆盖

`DocumentService` 在「删除」和「重复上传覆盖」时调 `EmbeddingStore.removeAll(Filter)`（按
`tenantId + docId` 删旧向量）。该方法对 InMemory / PGVector 稳定支持；**Milvus 需用上面第 4 步实测确认**：
若重传后旧内容仍被召回，说明该 Milvus 版本的 metadata-filter 删除未生效，代码已 `try/catch
UnsupportedOperationException` 降级（keyword 镜像仍清，但向量残留）。回退方案：重传前先
`DELETE /rag/documents/{docId}` 再 PUT，或给 store 包一层按 docId 的显式删除。

## 文档格式与维度

- 支持格式：Tika 覆盖 PDF / DOC(X) / XLS(X) / PPT(X) / HTML / MD / TXT 等。加密 PDF / 损坏文件
  会被解析为「无正文」→ 上传返回 `400` 带 `X-Error`。
- **维度坑**：embedding 维度 = Milvus collection 维度。默认 `nomic-embed-text`=768。换 `bge-m3`(1024)
  必须先 drop 旧 collection 再重建，否则维度不匹配。详见 CLAUDE.md「切换 Embedding Provider」。

## 可选增强（默认不开，控成本）

```bash
# 提精度：rerank（需 JINA_API_KEY）
--app.rag.rerank.enabled=true --app.rag.rerank.type=jina
# 中文召回：hybrid + HanLP 分词
--app.rag.hybrid.enabled=true --app.rag.hybrid.tokenizer=hanlp
```
