# OpenTelemetry GenAI 分布式追踪

把一次 chat 请求记成一棵 **span 树**（OpenTelemetry，遵循 GenAI 语义约定），与现有 Micrometer
指标（`gen_ai.client.*` counter/timer）正交：一个出聚合指标看趋势，一个出 span 看单请求的调用链
与耗时分解。默认关（`app.observability.otel.enabled=false`），零新依赖冲突（用 OTLP HTTP，不引 grpc）。

包位置：`observability/otel`。

## 组成

| 类 | 作用 |
| --- | --- |
| `OtelChatModelListener` | `@Component` 实现 `ChatModelListener`，自动加入 `LlmConfig` 注入的 `List<ChatModelListener>`（无需改 `LlmConfig`）。`onRequest` 起 CLIENT span、`onResponse`/`onError` 收 span 并写 `gen_ai.*` 属性 |
| `OtelTracingConfig` | 开启时建 OTel SDK（OTLP HTTP exporter + resource + sampler）；关闭时提供 no-op Tracer 兜底，listener 照常注入、span 走空操作 |
| `OtelTracingProperties` | `app.observability.otel.*` 配置 |

## span 属性（GenAI 语义约定）

CLIENT span，名 `chat {model}`，含：

- `gen_ai.operation.name` = `chat`
- `gen_ai.system` = provider（`OPEN_AI`→`openai` / `ANTHROPIC`→`anthropic` / `OLLAMA`→`ollama` …）
- `gen_ai.request.model` / `gen_ai.response.model`
- `gen_ai.request.messages`（请求消息条数）
- `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens`
- `gen_ai.response.finish_reasons`
- `gen_ai.client.duration_ms`（便于在 span 属性里直接看时长；span 起止时间也已隐含时长）
- `tenant.id` / `enduser.id`（复用 `security/TenantContext`，租户归属）
- 出错：`error.type` + recordException + span status = ERROR

## 开关与配置

`application.yml`（默认值）：

```yaml
app:
  observability:
    otel:
      enabled: false
      endpoint: http://localhost:4318/v1/traces   # OTLP HTTP collector
      service-name: langchain4j-app
      sampler: parentbased_always_on   # | always_on | always_off | traceidratio
      sampler-ratio: 1.0               # 仅 traceidratio 生效
      export-timeout-ms: 30000
```

| key | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关。关闭时不建 SDK/exporter，listener 走 no-op tracer，启动不失败、零开销 |
| `endpoint` | `http://localhost:4318/v1/traces` | OTLP HTTP（HTTP/protobuf）collector 端点 |
| `service-name` | `langchain4j-app` | 写进 resource 的 `service.name` |
| `sampler` | `parentbased_always_on` | 采样策略 |
| `sampler-ratio` | `1.0` | `traceidratio` 时的采样比例 |
| `export-timeout-ms` | `30000` | exporter 批量导出超时 |

## 怎么跑

起一个 collector（Jaeger all-in-one 自带 OTLP HTTP 4318 + UI 16686）：

```bash
docker run -d -p 4318:4318 -p 16686:16686 jaegertracing/all-in-one:1.57
```

开开关起应用并发一次请求：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--app.observability.otel.enabled=true
curl -X POST 'localhost:8080/chat?chatId=u1' -H 'Content-Type: application/json' \
  -d '{"message":"用三句话介绍 LangChain4j"}'
```

打开 Jaeger UI（`http://localhost:16686`），service 选 `langchain4j-app`，能看到 `chat <model>`
的 CLIENT span 及其 GenAI 属性。multi-agent / reflexion 等 fan-out 场景下每次 LLM 子调用各出一条
span，配合 `TraceIdFilter` 的 `traceId`（MDC）可跨日志与 span 串起来。

## 设计取舍

- **为什么 no-op tracer 兜底**：listener 要能一直加入 `List<ChatModelListener>`，就得是无条件
  `@Component`，构造需要 `Tracer`。关闭时用 `OpenTelemetry.noop()` 的 tracer（`@ConditionalOnMissingBean`），
  listener 无需自己判开关、span 空操作、启动不失败。
- **不注册第二个 ChatModel Bean**：listener 只观测，遵守仓库「只能有一个 ChatModel Bean」的约束。
- **OTLP HTTP 而非 gRPC**：本仓库为 Milvus 把 grpc 钉死在 1.59.1，HTTP exporter 走 OkHttp/JDK
  不引 grpc，避开版本冲突。
- **本地 SDK 实例、不设 GlobalOpenTelemetry**：避免多实例 / 测试互相污染。
- **与 Micrometer 正交**：`MetricsChatModelListener` 出 `gen_ai.client.*` 聚合指标（含 Anthropic
  prompt caching 的 cache_read/cache_write），本 listener 出 span 树；两者可同时开。

## 测试

`OtelChatModelListenerTest`（确定性、不连模型/collector/网络）：用 OTel SDK 的
`InMemorySpanExporter` 断言 —— 一次 response 导出一条 `chat gpt-4o-mini` CLIENT span 且属性齐全
（system/model/tokens/finish_reasons/tenant）；error 路径导出 ERROR span 带 `error.type` 与
exception event；no-op tracer 路径不导出任何 span。
