# Code Interpreter Action（深度 Agent · `code_exec`）

给深度 Agent（`ai/agent`，开放式 plan→act→observe 循环）加一个 **`code_exec`** 动作：让模型自己写一段 **Java 源码**，在受控的 JShell 沙箱里执行，把 stdout + 最后表达式的值喂回循环作为下一步依据。

补齐「模型自己算数 / 转格式 / 跑确定性逻辑」这类**不该靠 LLM 心算、也不值得专门造工具**的长尾计算需求——验证「一个能执行任意代码的动作也能安全地插进 ReAct 循环」（护栏在动作内，循环不感知）。

- 默认关，零新依赖（JDK 内置 `jdk.jshell.JShell`）。
- 属于 deep-agent 「真实能力动作」家族：`rag_search`（查文档）/ `nl2sql_query`（查库）/ `mcp_call`（外部工具）/ **`code_exec`（跑代码）**。

## 开关与装配

仅当 **两个开关同时为 true** 才装配（双 property `@ConditionalOnProperty`，与 `Nl2SqlAction` 一致）：

```yaml
app:
  deep-agent:
    enabled: true          # 父开关：深度 Agent 循环本体
    code-exec:
      enabled: true        # 子开关：本动作
```

任一为假：`CodeExecAction` / `CodeExecProperties` Bean 都不存在，动作根本不出现在可用清单里，模型不会尝试调用一个不存在的能力。默认（两者 false）零装配、零开销。

动作走 `@Component` 组件扫描，由 `DeepAgentService` 的 `List<AgentAction>` 自动收集——**加这个能力没改循环一行代码**，这正是 `AgentAction` 插件式设计的意义。

## 配置项 `app.deep-agent.code-exec.*`

| key | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | `false` | 子开关（父开关 `app.deep-agent.enabled`）。 |
| `timeout-ms` | `3000` | 单次执行墙钟超时（ms）。超时即中断并回可纠错文本，绝不无限等。 |
| `max-output-chars` | `2000` | 回传给模型的输出（stdout + 表达式值）字符上限，超出截断并加标记，防 scratchpad 爆。 |
| `max-source-chars` | `4000` | 允许提交的源码字符上限，超出直接拒绝（挡超大 payload / 上下文轰炸）。 |
| `block-unsafe-apis` | `true` | 是否静态拦截危险 API 源码（网络 / 文件 / 进程 / `System.exit` / 反射逃逸）。 |

默认值刻意保守——code_exec 是「让模型写代码并执行」的高风险能力，宁可小而稳。

## 模型怎么用

动作对模型的描述（`description()`）：

> 执行一段 Java 代码做精确计算/数据转换/确定性逻辑；actionInput 直接填 Java 源码（可多条语句，用表达式或 `System.out.println` 产出结果）。返回 stdout 与最后表达式的值。仅用于计算/转换等纯逻辑，禁止访问网络/文件/进程；需要事实用 `rag_search`、查库用 `nl2sql_query`。

`actionInput` 直接是 Java 源码，例如：

- 表达式求值：`2 + 3 * 4` → 返回 `14`
- 多语句 + 打印：`int s=0; for(int i=1;i<=100;i++) s+=i; System.out.println(s);` → 返回 `5050`

## 护栏与执行链路

`run(input)` 顺序：

1. **禁用判定**：`props.enabled=false` → 回「已禁用」提示（Bean 已双开关兜住，这层是直接构造测试禁用路径用）。
2. **空入参守卫** → 回可纠错文本。
3. **源码长度**：超 `maxSourceChars` → 拒绝。
4. **危险 API 静态 denylist**（`blockUnsafeApis=true`）：小写子串匹配网络 / 文件 / 进程 / 退出 / 反射逃逸等 token，命中即拦截 + 打 WARN 日志（带 `TenantContext` 租户）。
5. **执行**（`JShellRunner`）：JShell **local 执行引擎**（同进程、不 fork 子 JVM、不联网），把源码按补全边界拆成 snippet 顺序 eval，捕获 stdout/stderr 汇到一个缓冲 + 收集每个表达式的 value。
6. **收口判定**：超时 / 编译或运行错误 / 空输出 / 截断 分别映射成对应可纠错文本。

**`run()` 绝不抛异常**——超时、超限、编译错误、运行异常、禁用、空入参全部映射成可纠错文本回给模型（符合 `AgentAction` 契约，`DeepAgentService` 另有兜底 catch）。

### 超时实现

eval 投到共享 daemon 线程池，`Future.get(timeoutMs)` 超时即 `cancel(true)`（interrupt 执行线程，能打断 `Thread.sleep` / IO 等**可中断点**）+ best-effort `JShell.stop()`，不等待直接返回超时文本。紧循环（`while(true){}`）等不可中断点无法真正杀死——这是 local 引擎的已知局限（Java 21 已移除 `Thread.stop`），但执行线程为 daemon，不挡 JVM 退出、不阻塞后续 run。

## 局限（诚实声明）

**这不是真沙箱**：JShell local 引擎与宿主 JVM 同进程，Java 21 已移除 `SecurityManager`，做不到真隔离。本动作只提供**尽力而为**的护栏：源码 denylist（静态、可被混淆绕过）+ 墙钟超时（紧循环杀不掉）+ 输出/源码长度截断。因此：

- **默认关**，只在明确知道风险、可信输入场景下开。
- 需要**强隔离**（真正挡住恶意代码读文件 / 联网 / 提权）时，应换成外部受限进程 / 容器（seccomp / gVisor / 只读 rootfs / 无网络 namespace）——列为**未来项**。

## 测试

`src/test/java/com/lrj/langchain4j/ai/agent/actions/CodeExecActionTest.java`，9 个确定性单测，真跑 JShell local 引擎但**不联网、不 fork 子进程**、每条设兜底超时，可重复：

- 算术表达式求值正确（`2+3*4` → 14）
- 输出超限截断
- `Thread.sleep(60000)` 死等触发墙钟超时被兜住
- 编译错误（`int x = ;`）回可纠错文本
- 危险 API（`java.net.Socket`）静态拦截、不执行
- 源码超长被拒绝
- `enabled=false` 禁用路径
- 空入参守卫
- 元数据（name/description）稳定

```bash
mvn test -Dtest=CodeExecActionTest
```

## 端到端跑一遍

```bash
APP_DEEP_AGENT_ENABLED=true APP_DEEP_AGENT_CODE_EXEC_ENABLED=true mvn spring-boot:run

curl -X POST localhost:8080/agent/run -H 'Content-Type: application/json' \
  -d '{"goal":"精确计算 (1234 * 5678) + 987654 / 3，用代码算不要心算"}'
# 期望：trace 中出现 action=code_exec，observation 带正确数值，stopReason=DONE
```

（需 tool-calling 能力的模型，如 Ollama `qwen2.5+` / `llama3.1+`。）

## 相关文件

- `ai/agent/actions/CodeExecAction.java` — 动作本体（护栏编排 + `run` 不抛）
- `ai/agent/actions/JShellRunner.java` — JShell local 引擎执行 + 超时 + 截断（helper）
- `ai/agent/actions/CodeExecProperties.java` — `app.deep-agent.code-exec.*` 绑定
- `ai/agent/actions/CodeExecConfig.java` — 条件化注册 properties Bean
- `ai/agent/AgentAction.java` — 动作接口（本动作实现它）
- `ai/agent/DeepAgentService.java` — 循环本体（`List<AgentAction>` 自动收集本动作）
- 深度 Agent 总览见 `docs/deep-agent.md`
