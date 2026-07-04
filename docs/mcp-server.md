# MCP Server（反向暴露本项目能力）

把本 app 的能力**反向**暴露成一个 MCP（Model Context Protocol）server，让外部 MCP 客户端
（Claude Desktop / Cursor 等）通过标准协议**调进来**用我们的工具。

这是 `ai/mcp`（MCP **client**：把外部 server 的工具桥进来给我们的模型用）的镜像：

| 方向 | 包 | 开关 | 谁调谁 |
| --- | --- | --- | --- |
| client（进） | `ai/mcp` | `app.mcp.enabled` | 我们的模型 → 外部 MCP server 的工具 |
| **server（出）** | **`mcpserver`** | **`app.mcp.server.enabled`** | **外部 MCP 客户端 → 我们的工具** |

默认关（`app.mcp.server.enabled=false`），零新依赖。

## 端点

`POST /mcp/server` —— MCP over streamable HTTP 的 JSON-RPC 2.0 单端点。**需 `X-Api-Key`**
（走现有鉴权 / 多租户 / 限流 / token 预算 filter 链；不在安全白名单里）。

支持三个方法：

| method | 说明 | result |
| --- | --- | --- |
| `initialize` | 握手，回协议版本 + 服务端能力（只有 tools）+ 服务端信息 | `{protocolVersion, capabilities:{tools:{}}, serverInfo:{name,version}}` |
| `tools/list` | 列出可用工具描述符 | `{tools:[{name, description, inputSchema}]}` |
| `tools/call` | 调用某个工具 | `{content:[{type:"text",text:...}], isError:bool}` |

无 id 的 `notifications/*`（如 `notifications/initialized`）回 `202` 空体，不产生 JSON-RPC response。

## 暴露的工具

| 工具名 | 能力 | 复用 | 装配条件 |
| --- | --- | --- | --- |
| `current_datetime` | 某 IANA 时区当前时间 | `ai/tools/DateTimeTool` | `app.mcp.server.enabled` |
| `rag_search` | 企业知识库向量检索（带 `[doc=ID]` 引用） | 主 RAG 链 `vectorRetriever` | `app.mcp.server.enabled` |
| `nl2sql_query` | 自然语言查业务库（只读 + 6 层 SQL 护栏 + 租户谓词） | `NlToSqlService` | `app.mcp.server.enabled` **且** `app.nl2sql.enabled` |

`nl2sql_query` 是**软依赖**：NL2SQL 关闭时这个工具的 `@Component` 根本不装配，也就不出现在
`tools/list` 里——外部客户端看不到、无从调用。加新工具 = 新增一个实现 `McpServerTool` 的
`@Component`（自动被 `McpServerService` 收集，无需改 service / controller），与 `ai/agent` 的
`AgentAction` 自动发现范式一致。

## 租户隔离与安全

- `POST /mcp/server` **需 `X-Api-Key`**：`ApiKeyAuthFilter` 据此在请求线程上绑定
  `TenantContext`。工具跑在同一线程上，`rag_search` / `nl2sql_query` 复用的下游本就带
  租户过滤（`vectorRetriever` 的 `dynamicFilter` / NL2SQL 的租户谓词），因此自动隔离。
- **协议层错误 vs 工具执行失败**分流：
  - 未知方法 / 未知工具 / 缺 `name` → JSON-RPC `error`（`-32601` / `-32602`）。
  - 工具执行抛异常 / 坏入参 → MCP 约定的 `isError=true` result（错误文本放进 `content`），
    让模型读到错误并自行改写重试，不打断 JSON-RPC 信封。

## 配置

```yaml
app:
  mcp:
    server:
      enabled: false            # 默认关
      server-name: langchain4j-app
      server-version: 1.0.0
      protocol-version: "2024-11-05"
```

## 怎么跑

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--app.mcp.server.enabled=true
```

初始化 + 列工具 + 调用（`<tenant-key>` 换成有效的 per-tenant API key）：

```bash
curl -s -X POST localhost:8080/mcp/server -H 'Content-Type: application/json' \
  -H 'X-Api-Key: <tenant-key>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

curl -s -X POST localhost:8080/mcp/server -H 'Content-Type: application/json' \
  -H 'X-Api-Key: <tenant-key>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

curl -s -X POST localhost:8080/mcp/server -H 'Content-Type: application/json' \
  -H 'X-Api-Key: <tenant-key>' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
       "params":{"name":"current_datetime","arguments":{"zoneId":"Asia/Shanghai"}}}'
```

## 接入 Claude Desktop / Cursor

把 MCP streamable-HTTP 客户端指向 `http://<host>:8080/mcp/server`，带 header
`X-Api-Key: <tenant-key>`。客户端发 `initialize` → `tools/list` 发现工具后即可 `tools/call`。

## 测试

`src/test/java/com/lrj/langchain4j/mcpserver/McpServerServiceTest.java` —— 确定性单测，用 stub
`McpServerTool`，不连模型 / DB / 网络：

- `initialize` 回协议版本 + serverInfo
- `tools/list` 返回预期描述符（名字 / 描述 / inputSchema）+ 同名工具首个胜出
- `tools/call` 派发到 stub、arguments 原样透传、结果包成 `content`
- 工具抛异常 → `isError=true` result（非 JSON-RPC error）
- 未知工具 / 缺 `name` → `-32602 INVALID_PARAMS`；未知方法 → `-32601`；notification 回 null

```bash
mvn -Dtest=McpServerServiceTest test
```

## 未来项

- resources / prompts 能力（当前 `capabilities` 只声明 tools）
- SSE 长连接的服务端主动推送（当前是 request/response 的 streamable HTTP）
- 更多工具（voice / vision / deep-agent 触发）——新增 `McpServerTool` 实现即可
