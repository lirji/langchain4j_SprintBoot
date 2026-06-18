# 深度 Agent（开放式 plan→act→observe 循环）

把「多 Agent」从固定 DAG（plan→并行 worker→synthesize 一锤子）升级到**开放式长程循环**：模型自己决定下一步、用工具、观察结果、再决策，直到目标达成或预算耗尽。`ai/agent` 包，`app.deep-agent.*`，**默认关**，零新依赖。

## 与现有 multiagent / reflexion 的区别

| | 形态 | 终止 | 记忆 |
| --- | --- | --- | --- |
| `multiagent` | 固定 DAG：plan 一次 → 并行 worker → synthesize | 一锤子（+ 可选一次 replan） | 无跨步记忆 |
| `reflexion` | generate → critique → improve | 有界轮数 | 无 |
| **deep-agent** | 开放式 plan→act→observe，每步动态选动作 | 预算/循环检测/finish | scratchpad 跨步工作记忆 |

深度 Agent 填的是「长程、轨迹由模型自己决定」的空白——它是 **Browser-use / Computer-use 的地基**（那两个只是往循环里插的动作面，没有循环就无从驱动）。

## 设计

显式 **ReAct 循环**，而非依赖 LangChain4j 原生 function-calling 的自动工具循环——为的是对每步有完全控制权，且结构化决策在所有 provider 上行为一致、确定性可单测（原生工具调用由主 `Assistant` 承担，二者互补）。

每步：
1. `AgentBrain`（结构化输出 `AgentDecision{thought, action, actionInput, note, finalAnswer}`）看 **目标 + scratchpad + 最近 history + 可用动作清单**，决定**下一步一个动作**。
2. 编排器执行动作（内置 `finish`/`delegate`，或注册的 `AgentAction`），把观察喂回。
3. 循环直到终止。

**循环对每步的控制（深度 Agent 的核心价值）**：
- **硬预算** `max-steps` —— 跑满判 `MAX_STEPS`，挡 runaway；
- **循环检测** —— 连续重复同一 (动作,入参) 达 `max-repeats` 判 `LOOP`；
- **工作记忆** scratchpad —— 模型用 `note` 沉淀结论，跨步重注入（`max-scratchpad-chars` 截断）；
- **子 Agent 派生** `delegate` —— 深度受 `max-depth` 限，挡无限自我派生；
- **逐步 trace** —— 每步 thought/action/observation 全留痕。

`stopReason` ∈ `DONE`（正常 finish）/ `MAX_STEPS` / `LOOP` / `ERROR`（brain 异常，不崩整 run）。

## 扩展：加一个动作

实现 `AgentAction`（`name` / `description` / `run`）并标 `@Component` 即被自动发现加入清单——加 RAG 检索 / NL2SQL / 调外部 API 等真实能力照此办理，**无需改循环**。`description` 是模型「何时调用」的唯一依据，写清楚。失败请返回可纠错文本而非抛异常（循环也会兜底 catch）。示例见 `ai/agent/actions/CurrentTimeAction`。

## 关键设计点

- **复用主 `ChatModel`**：`AgentBrain` 程序化 `AiServices.builder` 构建、不带 ChatMemory（每步显式重注入 scratchpad+history，单步可重复可测），走主 `ChatModel`——已挂 metrics + per-tenant token 预算 listener，所以**深度 Agent 的 token 消耗自动纳入配额**，不新建 ChatModel Bean（同 `Judge`/`Planner` 套路）。
- **默认关、零回归**：整个 `DeepAgentConfig` + `AgentController` + 示例动作条件化在 `app.deep-agent.enabled=true`。
- **provider 无关**：结构化输出在 ollama/openai/anthropic/gemini/deepseek 上一致（需 tool-calling/JSON-schema 能力模型）。

## 关键文件

- `ai/agent/AgentBrain.java` — ReAct 单步决策 AiService（结构化 `AgentDecision`，无记忆）
- `ai/agent/AgentDecision.java` — `{thought, action, actionInput, note, finalAnswer}` 结构化输出
- `ai/agent/AgentAction.java` — 可插拔动作接口（`@Component` 自动发现）
- `ai/agent/DeepAgentService.java` — 循环本体：预算 / 循环检测 / scratchpad / delegate / trace
- `ai/agent/actions/CurrentTimeAction.java` — 示例动作
- `config/DeepAgentConfig.java` — `@ConditionalOnProperty` 装配（brain 走主 ChatModel）
- `controller/AgentController.java` — `POST /agent/run`（同步）+ `/agent/run/async`（异步，投 `async` 引擎）
- `ai/agent/AgentRunListener.java` — 顶层 run 收尾钩子（Browser-use 关页面用）
- `ai/agent/browser/` — Browser-use：`BrowserSession` 接口 + `PlaywrightBrowserSession`（按线程懒加载无头 Chromium）+ `BrowserOpenAction`/`BrowserClickAction`
- `DeepAgentServiceTest`（10）+ `browser/BrowserActionsTest`（3）— 确定性单测（finish / 动作+观察 / 未知动作恢复 / 预算 / 循环检测 / scratchpad / 委派深度上限 / 委派关闭 / onRunEnd 清理[含 brain 异常] / browser 动作透传+关闭）
- `eval/eval-cases-agent.json` — eval `type:"agent"` 黄金集（3 条，校验 stopReason/步数/最终答案）

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/agent/run` | 同步：body `{"goal":"..."}` → 返回 `{goal, steps[], finalAnswer, stopReason, depth}`（需 `app.deep-agent.enabled=true`）。短目标用。走 `/chat` 同款鉴权链；落 default 限流族 |
| POST | `/agent/run/async` | 异步：body `{"goal":"...","webhookUrl"?:"..."}` → 立即返回 `AsyncTask`（PENDING+taskId），循环投后台 `multiAgentExecutor`。取结果：`GET /tasks/{id}` 轮询 / `GET /tasks/{id}/stream` SSE / webhook 终态回推（复用现成 `async` 引擎，同 multi-agent async）。长目标用 |

## 异步化（长目标）

深度 Agent 一次 run 可能多步 LLM 调用、几十秒起，同步端点易超时。`/agent/run/async` 复用现成 `async` 任务引擎（`AsyncTaskService.submitDeepAgent`，软依赖深度 Agent）：投后台 `multiAgentExecutor`（`MdcCopyingTaskDecorator` 已透传 MDC + `TenantContext`，所以子线程的 brain LLM 调用、audit、token 配额都正确归属租户），结果是 `DeepAgentService.Run`，三种取法（轮询/SSE/webhook）与 multi-agent async 完全一致。

## Browser-use（`app.deep-agent.browser.enabled`，默认关）

把 Playwright 无头 Chromium 网页自动化做成 `AgentAction` 插进循环——验证「动作面只是往循环里插的工具，循环本身不变」：

- `browser_open`（导航到 URL，**执行页面 JS** 后返回标题/可见文本/链接列表）+ `browser_click`（按链接文本点击）。相对纯 HTTP fetch 的价值在于 JS 渲染。
- `PlaywrightBrowserSession` **按线程懒加载** Playwright+Browser+Page（线程隔离规避 Playwright 非线程安全），run 结束经 `AgentRunListener.onRunEnd` 关闭（`BrowserOpenAction` 实现该钩子，`DeepAgentService` 顶层 run 收尾时回调，brain 抛异常也照关）。
- 条件化在 `app.deep-agent.browser.enabled=true`：关闭时 browser 动作 + session 全不装配、**Chromium 不下载**。开启后首次需装浏览器二进制：
  ```bash
  mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
  ```
- v1 = 导航 + 按链接文本点击 + 读渲染文本；**表单输入 / 截图 / 坐标点击 = 未来项**（即完整 computer-use 的方向 C）。
- 3 个确定性单测（假 `BrowserSession` 验证动作透传 + onRunEnd 关闭），不连真浏览器。

## 怎么跑

```bash
APP_DEEP_AGENT_ENABLED=true mvn spring-boot:run   # 需 tool-calling / JSON-schema 能力模型

curl -X POST localhost:8080/agent/run \
  -H 'X-Api-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"goal":"现在上海几点？比北京时间晚还是早？"}'
# → {"goal":..., "steps":[{n,thought,action,actionInput,observation}...],
#    "finalAnswer":..., "stopReason":"DONE", "depth":0}
```

> 多参数覆盖用 env var（relaxed binding 稳），别堆 `-Dspring-boot.run.arguments` 逗号——见 CLAUDE.md 注意事项。

## eval `type:"agent"`

`set=agent` 跑 `eval-cases-agent.json`（3 条：纯常识应一步 finish / 调 current_time 工具 / 多步比较时区）。dispatch 在 `EvaluationRunner.invokeAgent`，把 `stopReason + 步数 + finalAnswer` 喂 Judge，`mustInclude:["stopReason: DONE"]` 守住「正常完成而非 LOOP/MAX_STEPS」。软依赖深度 Agent（没开 flag 时跑到该 case 才报清晰错误）。需 `app.deep-agent.enabled=true` + 真模型：

```bash
APP_DEEP_AGENT_ENABLED=true mvn spring-boot:run
curl -X POST 'localhost:8089/eval/run?set=agent&runs=3' -H 'X-Api-Key: <key>'
```

## 未来项

- **更多动作**：RAG 检索 / NL2SQL / MCP 工具桥接进 `AgentAction`（实现接口 + `@Component` 即可）
- **Browser-use 进阶**：表单输入 / 截图 / 坐标点击
- **Computer-use（方向 C）**：Docker 桌面沙箱 + 截图/点击工具，作为又一组 `AgentAction` 插进本循环
- **取消感知**：长 run 中途取消时让循环 check `Thread.interrupted()` 提前退出（当前异步取消是 best-effort）
