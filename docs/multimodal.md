# 多模态文档理解（Vision / 图像入库 + 视觉对话）

把「文档理解」从纯文本延伸到**图像**：图片、图表、扫描件经视觉模型转成文本后走现有 RAG 全链，或直接看图作答。`app.vision.*`，**默认关**，零新依赖（复用 `langchain4j-open-ai` / `langchain4j-ollama` 已有的多模态能力）。

## 解决什么

之前上传链路靠 Apache Tika 抽纯文本（`DocumentTextExtractor`）：

- 图片（png/jpg…）→ Tika 抽不出文字 → `no extractable text` → 400，图片完全进不了知识库；
- 含图表/示意图的文档 → 图里的信息被静默丢弃；
- 扫描件（图片格式）→ 同样抽不出文字。

视觉模型把「图像语义描述 + 可见文字 OCR 转写」一次产出成文本，补上这三块盲区。

## 分三段（phased）

| 段 | 能力 | 入口 | 落地 |
| --- | --- | --- | --- |
| ① 图像入库增强 RAG | 上传图片 → 视觉模型描述/OCR → 文本入库 → chunk/embed/检索/引用全链 | `POST /rag/documents`（multipart 图片） | ✅ |
| ② 视觉对话 | 看图直接作答，不入库，单轮 | `POST /chat/vision`（multipart `image` + `message`） | ✅ |
| ③ 扫描件 OCR | 图片格式扫描件的文字转写 | 同 ①（caption 指令内含「逐字转写」，OCR 与描述同一次调用） | ✅（图片格式） |

> ③ 中**PDF 格式的扫描件**（需逐页渲染成图）目前未做——Tika 对扫描 PDF 抽不出文字会照常 400。要支持需引入 PDFBox 渲染页面再喂视觉模型，作为未来项。图片格式（jpg/png 扫描页）已覆盖。

## 设计要点

- **provider 三向解耦**：`app.vision.provider` 与 `app.llm.provider`（chat）、`app.embedding.provider`（向量化）完全独立——视觉模型可单独指向 `gpt-4o`，chat 仍走本地 Ollama。同 embedding 解耦思路。
  - `openai-compat`（默认）：云 OpenAI / Azure / vLLM 等任意 OpenAI 兼容多模态端点（`gpt-4o` / `gpt-4o-mini` / `qwen2.5-vl`）
  - `ollama`：本地多模态模型（`llava` / `qwen2.5-vl` / `llama3.2-vision`）
- **不注册第二个 `ChatModel` Bean**：LangChain4j `@AiService` 自动发现要求进程里只有一个 `ChatModel` Bean（`@Primary` 都不顶用，见 `LlmConfig` / CLAUDE.md）。所以视觉 `ChatModel` 由 `VisionConfig` 直接构造、塞进 `DefaultVisionModel` 内部持有，**不暴露成 Bean**——跟 `buildJudgeChatModel` / grounding checker 同套路。对外只暴露自定义 `VisionModel` 接口。
- **caption 指令同时覆盖描述 + OCR**：一次调用产出「图像语义 + 图表数值趋势 + 逐字文字转写」，避免两次调用。指令可在 `app.vision.caption-prompt` 覆盖。
- **软依赖、零回归**：`VisionModel` 在 `MultimodalDocumentExtractor` 里是 `ObjectProvider` 软依赖。关闭时上传文本文档完全不受影响；上传图片得到清晰 400（`set app.vision.enabled=true`），不是 NPE。
- **入库后即普通文档**：图片转出的文本经 `DocumentService.upload` 入库，`file_name=<图片名>` → 检索命中后引用为 `[doc=chart.png#2]`，多租户/版本覆盖/生命周期删除全复用。
- **视觉对话单轮无记忆**：`/chat/vision` 刻意不带 ChatMemory，看图作答语义清晰、可重复。要多轮可后接 ChatMemory。

## 生产硬化（A/B/C）

落地三件「填窟窿」的硬化项，跟项目既有的多租户/限流/配额/审计基线对齐：

- **A 观测 + 配额**：视觉 `ChatModel` 由 `VisionConfig` 灌入全部 `ChatModelListener`（`Metrics` / `TokenBudget` / `Logging`）。否则视觉调用既不进 Prometheus 指标、也不回填 per-tenant 日 token 预算 = 绕过配额。`TokenBudgetChatModelListener` 从 `TenantContext` 拿租户归属，请求线程上调用 → 归属正确。（注：`/rate-limit` 的 `ingest` family 不**预拦**，但 token 用量仍被 `TokenBudgetTracker` 记账可见。）
- **B caption 缓存**：`DefaultVisionModel` 对 `caption()` 按图内容 **SHA-256 去重**（有界 LRU，`app.vision.caption-cache-size` 默 256，0=关）。同图重复上传/多次入库直接复用，省掉重复且昂贵的视觉调用。`answer()`（/chat/vision）不缓存——问题随请求变化。
- **C 图像内容安全**：caption/OCR 出的文本是**不可信外部输入**（图里可藏注入指令、转写可能带 PII），且它绕过了 `@AiService` 的 `@InputGuardrails`/`@OutputGuardrails`（视觉是独立构造的 ChatModel）。`VisionContentGuard.sanitizeForIngest` 入库前过闸：
  - **注入 → 阻断**：复用 `PromptInjectionDetector`（规则 + 可选 LLM，受 `app.guardrail.injection.*` 控制），命中拒绝入库（400）+ 审计 `guardrail.injection_detected`。阻断而非清洗——被投毒的图不该进库。
  - **PII → 脱敏**：`PiiDetector.redact` 把 email/手机/身份证替换成 `[REDACTED-类别]` 再入库 + 审计 `guardrail.pii_redacted`。脱敏而非阻断，保留文档可用。

## 关键文件

- `ai/vision/VisionModel.java` — 视觉抽象接口（`caption` 入库用 / `answer` 对话用）
- `ai/vision/DefaultVisionModel.java` — base64 + `UserMessage.from(ImageContent, TextContent)` → `chat()`，含字节上限 + 空图守卫 + **按图 SHA-256 去重的有界 LRU caption 缓存（B）**
- `ai/vision/VisionConfig.java` — `@ConditionalOnProperty(app.vision.enabled=true)`，按 provider 建视觉 `ChatModel`（不注册 Bean）+ **灌入 `ChatModelListener`（A：指标/配额）**
- `ai/vision/VisionContentGuard.java` — **入库前安全闸（C）**：注入阻断 + PII 脱敏 + 审计
- `ai/vision/VisionProperties.java` — `app.vision.*` 绑定 + 内置中文 caption/OCR 指令
- `ai/guardrail/PiiDetector.java` — 新增 `redact(text)`（C 复用）
- `rag/lifecycle/MultimodalDocumentExtractor.java` — 上传统一入口：图片→视觉→**过 `VisionContentGuard`**、其余→Tika；MIME/扩展名识别
- `controller/DocumentController.java` — multipart 上传改走 `MultimodalDocumentExtractor` + 图片字节守卫
- `controller/VisionController.java` — `POST /chat/vision`（条件化）
- `MultimodalDocumentExtractorTest`（8）+ `DefaultVisionModelTest`（5，缓存）— 确定性单测（路由 / MIME / 未启用报错 / 空结果 / 注入阻断 / PII 脱敏 / 缓存命中）

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/rag/documents`（multipart `file`） | 图片 → 视觉描述/OCR 入库（需 `app.vision.enabled=true`）；其余格式走 Tika。需 `SCOPE_ingest` |
| POST | `/chat/vision`（multipart `image` + `message`） | 看图作答，不入库（需 `app.vision.enabled=true`）。返回 `{reply}`。走 `/chat` 同款鉴权链 |

## 怎么跑

云 OpenAI（gpt-4o-mini 即支持图像输入）：

```bash
OPENAI_API_KEY=sk-... APP_VISION_ENABLED=true APP_VISION_MODEL_NAME=gpt-4o-mini \
  mvn spring-boot:run

# ① 图片入库（自动转描述 + OCR）
curl -X POST localhost:8080/rag/documents \
  -H 'X-Api-Key: <ingest-key>' \
  -F 'file=@chart.png'
# 然后正常 RAG 提问，图里的数据/文字已可被检索 + 引用

# ② 看图直接作答
curl -X POST localhost:8080/chat/vision \
  -H 'X-Api-Key: <key>' \
  -F 'image=@chart.png' \
  -F 'message=这张图的 Q2 营收是多少？'
```

本地 Ollama（零成本）：

```bash
ollama pull qwen2.5-vl     # 或 llava / llama3.2-vision
APP_VISION_ENABLED=true APP_VISION_PROVIDER=ollama \
  APP_VISION_BASE_URL=http://localhost:11434 APP_VISION_MODEL_NAME=qwen2.5-vl \
  mvn spring-boot:run
```

> 多参数覆盖用 env var（relaxed binding 稳），别堆 `-Dspring-boot.run.arguments` 逗号——见 CLAUDE.md 注意事项。

## 未来项

- PDF 格式扫描件的逐页渲染（PDFBox）→ 每页喂视觉模型
- 文档内嵌图片提取（PDF/PPT 里的图）单独 caption 后并入正文
- eval `type:"vision"` 黄金集（需图像 fixture + 真视觉模型，确定性单测覆盖不到看图质量）
