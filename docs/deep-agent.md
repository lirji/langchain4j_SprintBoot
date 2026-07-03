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
- **三维预算**（任一超限即停）—— 步数 `max-steps`（`MAX_STEPS`）/ 墙钟 `max-wall-clock-ms`（`TIMEOUT`，>0 开）/ 近似 token `max-tokens`（`BUDGET`，>0 开，字符/4 估算，循环内安全上限，正交于全局日配额），挡 runaway；
- **循环检测** —— 滑窗（`loop-window`，实际取 `max(loop-window, max-repeats)`）内同一 (动作,入参) 出现达 `max-repeats` 判 `LOOP`；能抓 `A→B→A→B` 震荡（旧逻辑只抓「连续完全相同」）；
- **工作记忆** scratchpad —— 模型用 `note` 沉淀结论，跨步重注入；溢出 `max-scratchpad-chars` 时**按 bullet 行压缩**（不再腰斩半行），`scratchpad-summary=true` 时把挤出的旧结论 LLM 摘成一条 bullet 保住信息、失败降级为丢弃最旧整条；
- **子 Agent 派生** `delegate` —— 深度受 `max-depth` 限，挡无限自我派生；
- **逐步 trace** —— 每步 thought/action/observation 全留痕。

`stopReason` ∈ `DONE`（正常 finish）/ `MAX_STEPS` / `TIMEOUT`（超墙钟预算）/ `BUDGET`（超近似 token 预算）/ `LOOP`（卡死重复，含震荡）/ `ERROR`（brain 异常，不崩整 run）/ `CANCELLED`（被取消：worker 线程被 interrupt，见下「取消感知」）。

> **Loop Engineering 视角**：这三维预算 + 滑窗循环检测 + scratchpad 摘要压缩，正是「循环工程」把 demo 级 `while(调模型)` 升级为生产级循环的关键——预算不只是步数、卡死不只是连续重复、工作记忆溢出不是盲砍。摘要器走独立 temp=0 判官模型（`buildJudgeChatModel`），与 `SummarizingChatMemory` 跨轮压缩同思路。

## 扩展：加一个动作

实现 `AgentAction`（`name` / `description` / `run`）并标 `@Component` 即被自动发现加入清单——加 RAG 检索 / NL2SQL / 调外部 API 等真实能力照此办理，**无需改循环**。`description` 是模型「何时调用」的唯一依据，写清楚。失败请返回可纠错文本而非抛异常（循环也会兜底 catch）。示例见 `ai/agent/actions/CurrentTimeAction`。

**已接入的真实能力动作**（验证「动作只是往循环里插的工具」）：

- `rag_search`（`RagSearchAction`，深度 Agent 开即装）—— 复用主 RAG 链的 `vectorRetriever`（已带租户 + category 过滤的 `dynamicFilter`，子线程里也按 `TenantContext` 隔离），返回带 `[doc=ID]` 标记的片段（id 走 `TaggedSourceContentInjector.inferId`，与主链引用格式一致），让模型在长程任务里**自己决定何时检索**——不再只能挂在 `Assistant` 的自动 augmentor 上被动触发。
- `nl2sql_query`（`Nl2SqlAction`，**仅 `app.deep-agent.enabled` 且 `app.nl2sql.enabled` 同时为 true 时装配**，多 property 的 `@ConditionalOnProperty` 要求全部命中——NL2SQL 关闭时这个动作根本不出现在可用清单里）—— 把自然语言问题交给受控 NL2SQL 链（6 层 SQL 护栏 + 只读执行 + 租户谓词都在 `NlToSqlService` 内，动作只透传），回传 SQL + 行数 + 解读；`guardBlocked` 时明确告知模型换问法。验证带护栏的高风险能力也能安全地作为一个动作插进循环。
- `mcp_call`（`McpToolAction`，**仅 `app.deep-agent.enabled` 且 `app.mcp.enabled` 同时为 true 时装配**）—— 把 MCP server 动态发现的整个外部工具集桥进循环：单个分派动作，构造时一次 `listTools()` 缓存工具目录写进描述，模型用 JSON `{"tool":"名","args":{...}}` 选具体工具，动作转成 `ToolExecutionRequest` 交给 `McpClient.executeTool`。不复用 `McpAssistant`（那是带原生 function-calling 的独立 AiService）——这里要让深度 Agent 循环自己控制每步，故直接持 `McpClient` 分派。验证「连动态发现的外部工具集也能整体作为一个动作插进循环」。

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
- `ai/agent/actions/RagSearchAction.java` — `rag_search` 动作（复用 `vectorRetriever`，带 `[doc=ID]` 引用）
- `ai/agent/actions/Nl2SqlAction.java` — `nl2sql_query` 动作（双 property 条件化，透传受控 `NlToSqlService`）
- `ai/agent/actions/McpToolAction.java` — `mcp_call` 动作（双 property 条件化，分派 `McpClient` 工具，目录进描述）
- `config/DeepAgentConfig.java` — `@ConditionalOnProperty` 装配（brain 走主 ChatModel）
- `controller/AgentController.java` — `POST /agent/run`（同步）+ `/agent/run/async`（异步，投 `async` 引擎）
- `ai/agent/AgentRunListener.java` — 顶层 run 收尾钩子（Browser-use 关页面用）
- `ai/agent/browser/` — Browser-use：`BrowserSession` 接口 + `PlaywrightBrowserSession`（按线程懒加载无头 Chromium）+ `BrowserOpenAction`/`BrowserClickAction`/`BrowserClickXyAction`（坐标点击）/`BrowserTypeAction`（表单输入）/`BrowserScreenshotAction`（整页截图存文件）/`BrowserSeeAction`（截图→视觉理解，双开 browser+vision 时装配）
- `DeepAgentServiceTest`（16）+ `browser/BrowserActionsTest`（11）+ `actions/RagSearchActionTest`（5）+ `actions/Nl2SqlActionTest`（4）+ `actions/McpToolActionTest`（7）— 确定性单测（finish / 动作+观察 / 未知动作恢复 / 步数预算 / 墙钟预算 TIMEOUT / token 预算 BUDGET / 连续+震荡循环检测 / scratchpad line-aware 压缩 + 摘要器压缩 / 委派深度上限 / 委派关闭 / onRunEnd 清理[含 brain 异常] / 取消感知[中断标志侦测+清理] / browser 动作透传+关闭+表单输入分隔+截图 / rag_search 引用格式·截断·空命中·异常降级 / nl2sql_query 结果格式·护栏拦截·异常降级 / mcp_call 目录进描述·JSON 分派·缺字段·坏 JSON·工具错误·异常降级）
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

- `browser_open`（导航到 URL，**执行页面 JS** 后返回标题/可见文本/链接列表）+ `browser_click`（按链接文本点击）+ `browser_click_xy`（按像素坐标点击）+ `browser_type`（表单输入：actionInput `CSS选择器=>文本`）+ `browser_screenshot`（整页截图存文件）+ `browser_see`（截图 → **视觉模型理解**）。相对纯 HTTP fetch 的价值在于 JS 渲染。
- `PlaywrightBrowserSession` **按线程懒加载** Playwright+Browser+Page（线程隔离规避 Playwright 非线程安全），run 结束经 `AgentRunListener.onRunEnd` 关闭（`BrowserOpenAction` 实现该钩子，`DeepAgentService` 顶层 run 收尾时回调，所有 browser 动作共享同一线程会话，brain 抛异常也照关）。
- **截图不回传 base64**：`browser_screenshot` 截整页存临时文件（`Files.createTempFile`），观察只回传**路径 + 字节数**——整页 PNG 的 base64 会瞬间撑爆 scratchpad。
- **`browser_see` 闭合「截图 → 理解」回路**（仅 `app.deep-agent.browser.enabled` 且 `app.vision.enabled` 双开时装配）：`BrowserSession.screenshotBytes()` 出原始 PNG → `ai/vision` 的 `VisionModel.caption`（留空 actionInput，整体描述）/ `answer`（带问题）→ 让模型「看」页面文本抽不出的内容（图表/布局/纯图片页/验证码）。视觉 `ChatModel` 已挂指标 + per-tenant token 预算 listener，这步视觉调用 token 也正确纳入配额。
- 条件化在 `app.deep-agent.browser.enabled=true`：关闭时 browser 动作 + session 全不装配、**Chromium 不下载**。开启后首次需装浏览器二进制：
  ```bash
  mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
  ```
- **坐标点击 `browser_click_xy`** 补 `browser_click` 文本匹配抓瞎的场景（图标/canvas/无文字按钮）：actionInput `x,y` → `page.mouse().click(x,y)`。配合 `browser_see`（视觉模型读出元素大致位置）形成 computer-use 式的「看坐标 → 点坐标」闭环。
- 已覆盖：导航 + 按链接文本点击 + 按坐标点击 + 读渲染文本 + 表单输入 + 整页截图 + 截图视觉理解。浏览器内动作面已基本完整；更重的 **Computer-use 桌面沙箱（方向 C：Docker 桌面 + 系统级截图/点击）** 仍是独立未来项。
- 11 个确定性单测（假 `BrowserSession` + 假 `VisionModel` 验证动作透传 + 坐标解析 + 表单输入分隔解析 + 截图委派 + 视觉 caption/answer 分流 + 空截图降级 + onRunEnd 关闭），不连真浏览器/视觉模型。

## 取消感知（异步 run 中途取消）

`/agent/run/async` 投后台后，`Future.cancel(true)` 会 **interrupt** worker 线程。循环**每步开头** check `Thread.currentThread().isInterrupted()`，侦测到就提前返回 `stopReason=CANCELLED`（把已沉淀的 scratchpad 作为 best-effort 答案带回）。

- **粒度**：当前若已在跑某一步（尤其 brain 的 LLM 调用），那一步会跑完——无法中止上游 LLM 生成，与流式 `/chat/stream` 的取消同款限制；下一步之前才停。比之前「best-effort、循环不 check」前进了一步：长 run 不会在取消后继续白烧后续步骤。
- **线程池卫生**：检测用非清除的 `isInterrupted()`，使所有嵌套 `depth` 都能侦测到并退出；顶层 `run()` 的 finally 里统一 `Thread.interrupted()` 清掉标志，避免污染 `multiAgentExecutor` 里复用本线程的下一个任务。

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

- **更多动作**：RAG 检索（`rag_search`）/ NL2SQL（`nl2sql_query`）/ MCP 工具桥接（`mcp_call`）均已落地。下一类候选：调外部 HTTP API / 发邮件等
- **Browser-use**：导航 / 文本点击 / 坐标点击（`browser_click_xy`）/ 表单输入（`browser_type`）/ 整页截图（`browser_screenshot`）/ 截图视觉理解（`browser_see`，接 `ai/vision`）全落地，浏览器内动作面已基本完整
- **Computer-use（方向 C）**：Docker 桌面沙箱 + 截图/点击工具，作为又一组 `AgentAction` 插进本循环
- **取消彻底化**：当前每步开头 check 中断标志（已落地），进行中的那一步仍会跑完；要中止进行中的步骤需让动作自身（尤其 LLM 调用）感知中断——受限于上游不暴露取消句柄
