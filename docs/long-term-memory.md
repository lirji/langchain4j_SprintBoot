# 长期记忆 / 用户画像落地

> 状态：**v1 已落地**，默认关（`app.memory.profile.enabled`），零新依赖，10 个确定性单测。
> 补的是「跨会话记住这个用户」的能力——正交于现有的**会话内**滑窗记忆。
> 关联：会话内记忆 → `memory/SummarizingChatMemory` + CLAUDE.md「记忆与 Reranking」；
> 注入范式 → `ai/CategoryChatService`；导航 → `CLAUDE.md`。

---

## 1. 做什么 / 跟现有记忆的区别

项目原有的 `ChatMemory`（`messages`/`tokens`/`summary` 三种滑窗）是**会话内**记忆：按 `chatId`
保留最近若干轮，**换个会话就忘**、跨会话不共享。

**长期记忆补的是另一条轴**：记住「关于这个**用户**、值得**跨会话**长期保留」的事实——
偏好（"偏好邮件联系"）、稳定属性（"企业版客户"）、反复诉求（"多次咨询退款政策"）。
下次该用户**任意新会话**进来，这些画像被召回注入，助手"记得"他。这就是 mem0 式的语义长期记忆。

| | 会话内记忆（ChatMemory，已有） | 长期记忆 / 画像（本落地） |
| --- | --- | --- |
| 键 | `chatId`（一次会话） | `(tenant, user)`（跨所有会话） |
| 内容 | 最近 N 轮原始消息 | 抽炼后的 durable 用户事实 |
| 生命周期 | 会话结束/滑出即忘 | 长期保留（带容量上限淘汰） |
| 注入 | 自动进 prompt 历史 | chat 前召回、拼进上下文 |

## 2. 架构（`memory/profile/` 包）

```
  chat 请求 ──► recall(tenant,user) ──► 注入"【用户长期记忆】…"到消息前 ──► Assistant.chat ──► reply
                                                                                      │
                              observe(原始 userMsg, reply) ──异步──► ProfileExtractor(temp=0)
                                                                          │ 抽 durable 事实
                                                                  UserProfileStore.add(去重+容量上限)
```

| 文件 | 职责 |
| --- | --- |
| `MemoryItem` / `MemoryFact` / `ExtractedMemories` | 记忆条目 + 抽取的结构化输出 |
| `ProfileExtractor` | `@AiService`（temp=0 判官模型）：一轮对话 → durable 用户事实。prompt 强约束「只抽持久、跳过一次性、绝大多数轮返回空」，含反例 |
| `UserProfileStore` + `InMemoryUserProfileStore` | 按 `(tenant,user)` 隔离存储；去重（归一相等/互为子串）+ 容量上限淘汰最旧 + per-key 锁串行化 RMW。默认内存，Redis 持久化作升级路径 |
| `UserProfileService` | `recall`（渲染最近 N 条 bullet）+ `observe`（**默认异步**抽取入库，失败被吞） |
| `UserProfileChatService` | 包装 `Assistant.chat`：召回注入 + 异步观察（跟 `CategoryChatService` 同范式，不改主 Assistant） |
| `config/MemoryProfileConfig` | `@ConditionalOnProperty(app.memory.profile.enabled)` 装配全部 + `profileExecutor` |
| `controller/MemoryController` | `/chat/memory`、`/memory/profile`（GET/DELETE） |

**复用链**：多租户鉴权（`X-Api-Key`→tenant+user）/ 限流 / 配额 / 审计全走现有安全链；`user` 取自
`TenantContext.current().userId()`（api-key 映射的用户）。

## 3. 配置 `app.memory.profile.*`

```yaml
app:
  memory:
    profile:
      enabled: false        # 默认关 → 对话链零变化
      store: in-memory      # in-memory（默认，重启即丢）| redis（持久化升级路径，按信号补）
      max-items: 50         # 每用户记忆上限，超出淘汰最旧
      recall-limit: 12      # chat 前注入召回最近多少条
      async: true           # 观察（抽取+入库）异步，不阻塞 chat 响应
```

## 4. 端点 / 怎么跑

```bash
APP_MEMORY_PROFILE_ENABLED=true APP_LLM_OLLAMA_MODEL_NAME=qwen3:14b SERVER_PORT=8081 \
  mvn spring-boot:run

# 第一轮：告诉助手一个偏好（会被异步抽进画像）
curl -X POST 'localhost:8081/chat/memory?chatId=s1' -H 'X-Api-Key: dev-key-tenantA-admin' \
  -H 'Content-Type: application/json' -d '{"message":"以后有事发我邮箱就行，我不看短信"}'

# 换一个全新会话 chatId=s2：助手应"记得"偏好邮件（画像被召回注入）
curl -X POST 'localhost:8081/chat/memory?chatId=s2' -H 'X-Api-Key: dev-key-tenantA-admin' \
  -H 'Content-Type: application/json' -d '{"message":"怎么联系我比较好？"}'

# 查看 / 删除画像（透明可审 + PII 合规删除）
curl 'localhost:8081/memory/profile' -H 'X-Api-Key: dev-key-tenantA-admin'
curl -X DELETE 'localhost:8081/memory/profile' -H 'X-Api-Key: dev-key-tenantA-admin'
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/chat/memory` | 记忆增强对话：召回注入 + 异步更新画像 |
| GET | `/memory/profile` | 列出当前用户的长期记忆 |
| DELETE | `/memory/profile` | 清空（PII 合规删除） |

## 5. 决策 / 坑 / 故意不做

**决策**：
- **独立端点而非改主 `/chat`**：跟 `/chat/category`、`/chat/sql` 一样新能力走新端点 + 默认关，
  主链零回归。生产要全局开就把注入折进 `/chat`（按信号）。
- **异步观察**：抽取是额外 LLM 调用，投后台不拖慢响应（跟 `SummarizingChatMemory` 异步压缩同理）。
- **注入走消息前缀**：简单、对 guardrail/RAG 透明；观察用**原始**消息（不含注入前缀）让抽取看到干净输入。
- **抽取走 temp=0 判官模型**：画像应稳定，不该同段对话每次抽出不同事实。

**坑**：
- **抽取宁缺毋滥**：prompt 强约束「绝大多数轮返回空」——记忆库塞满噪声反而拖累注入质量、涨 token。
- **去重是 lexical（v1）**：归一相等/互为子串能去掉「偏好邮件」vs「偏好邮件联系」，但语义近似非子串
  （「偏好邮件」vs「偏好通过邮件联系」）抓不到——要 embedding 消歧（后续）。单测显式标注了这条边界。
- **多副本**：内存 store 是进程内 + per-key 锁（限单 JVM）。多 pod 需 Redis 实现 + Redis 层锁。

**故意不做**（决策记录）：
| 项 | 为什么 |
| --- | --- |
| embedding 语义检索/消歧 | v1 用 lexical 去重 + 全量召回（recall-limit 截断）够演示；大画像库再上向量 |
| 记忆 update/forget（mem0 的改写/遗忘） | v1 只 add + 去重 + 容量淘汰；带冲突消解的 update 是后续 |
| 自动注入主 `/chat` | 默认关 + 独立端点更安全；按信号折入 |
| Redis 持久化实现 | 接口已留，按信号补（参考 `RedisChatMemoryStore`） |

## 6. 测试

- `InMemoryUserProfileStoreTest`（5）：去重 / 容量淘汰 / 租户·用户隔离 / 空文本跳过 / 清空
- `UserProfileServiceTest`（5）：召回格式 + recall-limit 截最近 / 观察抽取入库 / 空抽取 no-op / 抽取异常被吞

抽取质量这类需连模型的断言走手动/eval，不在单测（同项目惯例）。

## 关联文档

- 会话内记忆三种滑窗 → `CLAUDE.md`「记忆与 Reranking」
- 注入包装范式 → `ai/CategoryChatService`
- 待完善项 → `docs/roadmap.md`
