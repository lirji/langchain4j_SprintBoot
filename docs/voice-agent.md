# 语音客服 Agent 落地

> 状态：**v1 turn-based + v2 SSE 半流式 均已落地**，默认关（`app.voice.enabled`），零新依赖（JDK `HttpClient` + 复用项目 `SseEmitter`）。
> 两条都用 stub 网关端到端真跑通（`/voice/chat` 单轮、`/voice/chat/stream` 分句流式）。真实 ASR/TTS 质量需配 provider（云 OpenAI / 本地 whisper+tts 网关）。
> 把「智能客服」从纯文本渠道（飞书）延伸到语音——补上 roadmap 里一直挂着的 IVR 那块的**核心环**。
> 关联：客服大脑复用 → `channel/CustomerServiceBrain`；意图路由 → `channel/feishu/FeishuIntent`；
> 工作流 → `docs/workflow-integration.md`；场景总览 → `docs/scenarios.md`；导航 → `CLAUDE.md`。

---

## 1. 做什么 / 核心洞察

**语音客服 = ASR（语音转文字）前置 + TTS（文字转语音）后置，中间夹的还是你已经做好的客服大脑。**

关键洞察：语音 Agent 真正的"智能"不在语音本身，而在已落地的那套——**意图路由（退款/投诉→工作流，其余→对话）+ 工作流审批 + RAG 对话 + 多租户/配额/审计**。
所以本落地**不重写大脑**，只做两件新事：① 把音频转成文字喂进大脑；② 把大脑的文字回复合成语音播回去。

```
  音频(用户说话) ─ASR→ 文字 ─→ CustomerServiceBrain ─→ 文字回复 ─TTS→ 音频(播给用户)
                                       │
                  意图分类 → 退款/投诉？→ WorkflowService.start（转人工/自动受理）
                                      └ 否 → Assistant.chat（RAG 对话）
```

**这次新增的、可复用的一块**：`CustomerServiceBrain`——把飞书 `route()` 里「文字进→意图分类→工作流或对话→文字出」
抽成渠道无关的 bean。语音直接用它，飞书后续也可收敛过来（v1 飞书保持原样，见第 6 节）。

## 2. 为什么是 turn-based 而不是实时全双工

实时全双工语音（边说边听、打断 barge-in、流式 ASR/TTS）要 WebSocket/WebRTC + VAD + 回声消除，工程量巨大且依赖重。
对齐项目「先打地基、被信号证明不够再加」的取向，v1 做 **turn-based**：一段完整音频上传 → 一段完整音频回复。
这覆盖了「网页/小程序按住说话」「IVR 录一句转一句」的主流客服场景，且**完全可测、可灰度**。

| | turn-based（v1） | 实时全双工（未来） |
| --- | --- | --- |
| 传输 | HTTP 上传一段音频 | WebSocket/WebRTC 流 |
| 延迟 | 整句等（ASR+脑+TTS 串行） | 边说边出，打断 |
| 依赖 | 一个 ASR/TTS provider | + VAD / 回声消除 / 流式编解码 |
| 适用 | 网页/小程序语音、IVR 录音转写 | 电话实时坐席替身 |

实时/电话 IVR（SIP/呼叫中心 webhook）= 明确的未来项（第 7 节），不预先做。

## 3. 架构与关键文件

新增 `voice/` 包 + 一个共享 `channel/CustomerServiceBrain`：

| 文件 | 职责 |
| --- | --- |
| `channel/CustomerServiceBrain.java` | **渠道无关客服大脑**：`reply(tenantId, chatId, text)` → `BrainReply{text, route, workflowInstanceId}`。复用 `FeishuIntent.classify` + `WorkflowService`（可选软依赖）+ `Assistant`。工作流挂起→播报"已转人工"，自动受理→播报 `StartResult.reply()`；其余→`Assistant.chat` |
| `voice/SpeechService.java` | 语音能力接口：`transcribe(audio, filename)→文字` / `synthesize(text)→Speech{audio, contentType}`。把 ASR/TTS provider 抽象掉 |
| `voice/OpenAiSpeechService.java` | OpenAI 兼容实现（JDK `HttpClient`，零新依赖，跟 `FeishuClient` 同款）：ASR 走 `POST {base-url}/audio/transcriptions`（multipart），TTS 走 `POST /audio/speech`（JSON）。base-url 可指 OpenAI / Azure / 本地 faster-whisper + openedai-speech / 任意 OpenAI 兼容网关 |
| `voice/VoiceConversationService.java` | 编排：ASR → `CustomerServiceBrain` → TTS，产出 `VoiceReply{transcript, replyText, route, audioBase64, audioContentType}`。空转写兜底（没听清不进大脑、不烧 token） |
| `voice/VoiceProperties.java` | `app.voice.*` 绑定 |
| `voice/VoiceConfig.java` | `@ConditionalOnProperty(app.voice.enabled)` 装配上面 + brain |
| `voice/VoiceStreamService.java` | SSE 半流式编排：整段 ASR → `Assistant.chatStream` 流式 token → `SentenceChunker` 分句 → 逐句 TTS → `audio-chunk` 事件（断连取消同 `/chat/stream`） |
| `voice/SentenceChunker.java` | 流式 token 攒整句的状态机（句末标点 + `min-chars` 阈值），纯逻辑可单测 |
| `controller/VoiceController.java` | `POST /voice/chat`（轮次）+ `POST /voice/chat/stream`（SSE 半流式）+ `POST /voice/transcribe`（只 ASR，调试） |

**复用链（零改动）**：多租户鉴权（`X-Api-Key` + `TenantContext`）/ 限流 / token 配额 / 审计 全部走现有安全链——
`/voice/**` 不进 permitAll，跟 `/chat` 一样要合法 key。chatId 隔离会话记忆同 `/chat`。

## 4. 配置 `app.voice.*`

```yaml
app:
  voice:
    enabled: false              # 默认关 → voice 相关 Bean 全不装配
    provider: openai            # 目前仅 openai 兼容；接别家在 SpeechService 加实现
    base-url: https://api.openai.com/v1   # 可指 Azure / 本地 whisper+tts 网关
    api-key: ${OPENAI_API_KEY:}
    asr-model: whisper-1        # 或 gpt-4o-transcribe；本地可填 faster-whisper 模型名
    tts-model: tts-1            # 或 gpt-4o-mini-tts
    tts-voice: alloy            # 音色
    tts-format: mp3             # mp3 | wav | opus ...
    language: ""                # ASR 语言提示（如 zh），留空自动检测
    timeout-seconds: 30
    max-audio-bytes: 26214400   # 上传音频上限（25MB，挡超大文件）
```

## 5. 怎么跑

```bash
# 起一个 OpenAI 兼容的语音后端（云 OpenAI，或本地 faster-whisper + openedai-speech）
OPENAI_API_KEY=sk-... APP_VOICE_ENABLED=true \
  mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081

# 上传一段音频提问（wav/mp3/m4a），带鉴权 key，可选 chatId 续会话
curl -X POST 'localhost:8081/voice/chat?chatId=u1' \
  -H 'X-Api-Key: <你的key>' \
  -F 'audio=@question.mp3'
# → {transcript, reply, route, audioContentType, audioBase64}
# 把 audioBase64 解码即得 TTS 语音回复

# 只测 ASR
curl -X POST 'localhost:8081/voice/transcribe' \
  -H 'X-Api-Key: <你的key>' -F 'audio=@question.mp3'
```

退款类语音（"我要退款"）→ 命中工作流意图 → 播报"已转人工审核，工单号 …"；
普通咨询 → 走 RAG 对话 → 播报答案。跟飞书渠道同一套大脑、同一套工作流/审批。

## 6. 决策记录 / 坑 / 故意不做

**决策**：
- **大脑抽成 `CustomerServiceBrain`、语音先用**：飞书 `route()` v1 不动（它还要推审批卡片，流程更重），
  只把「分类→工作流/对话→文字」这段共性抽出来给语音用。**后续可让飞书也收敛到 brain**，consolidation 留作小重构。
- **SpeechService 抽象 + OpenAI 兼容实现**：跟项目 chat/embedding 用 OpenAI 兼容协议（vLLM/DeepSeek）一个路子——
  base-url 一换即可指云 OpenAI / Azure / 本地 whisper+tts，不锁死厂商、零新依赖（JDK `HttpClient`）。
- **工作流挂起在语音里只播报"转人工"**：审批是异步的（人审完经 `WorkflowTerminalEvent` 回推），
  语音是同步轮次拿不到终态，所以挂起时播"已转人工、稍后通知"，自动受理时播 `StartResult.reply()`。
  终态语音回拨（审批完成后 TTS 拨回用户）属电话/IVR 范畴，未来项。

**坑**：
- ASR 错字会放大下游：口音/噪声转错关键词（"退款"听成"推广"）会误判意图。v1 接受，未来可加置信度阈值 + 复述确认。
- TTS 念引用标记难听：RAG 回复里的 `[doc=file#3]` 念出来很怪。语音路播报前**剥掉引用标记**（`VoiceConversationService` 做），文字 transcript 里保留。
- 大音频/超时：`max-audio-bytes` + `timeout-seconds` 兜底；超大文件直接 400。
- 多租户：音频也是用户数据，走同一鉴权链；审计落 ASR 文字（PII 注意，按需脱敏）。

**故意不做（决策记录）**：
| 项 | 为什么 |
| --- | --- |
| 实时全双工 / barge-in | WebRTC+VAD+回声消除工程量大，turn-based already 覆盖主流；按信号再上 |
| 电话 IVR（SIP / 呼叫中心 webhook） | 要对接 telephony provider（Twilio/阿里云呼叫中心），是独立集成项 |
| 自训 ASR/TTS | provider 即可，自训是模型工程不是本项目范畴 |
| 声纹识别 / 情绪识别 | 超出客服核心闭环，按业务信号再加 |

## 7. 分阶段

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| **V1 轮次语音闭环** | `SpeechService`+OpenAI 实现 + `CustomerServiceBrain` + `VoiceConversationService` + `/voice/chat` + 引用剥离 + 空转写兜底 | ✅ 已落地（stub 网关端到端真跑通） |
| **V2 SSE 半流式** | `/voice/chat/stream`：整段 ASR → 流式 LLM token → `SentenceChunker` 分句 → 逐句 TTS → SSE `audio-chunk` 推回（边生成边播）。`VoiceStreamService` + `SentenceChunker`（5 单测） | ✅ 已落地（stub 网关 SSE 真跑通：2 句 → 2 个 audio-chunk） |
| V2+ 全双工实时（按信号） | WebSocket/WebRTC 流式 ASR + VAD + barge-in（边说边打断） | ⏳ 依赖重，留 V3 前 |
| V3 电话 IVR（按信号） | telephony provider webhook + 终态语音回拨 | ⏳ |
| V4 大脑收敛（按信号） | 飞书渠道也改用 `CustomerServiceBrain`，去重路由逻辑 | ⏳ |

> **V2 是"半双工流式"**（上行整段、下行流式分句 TTS），不是全双工——回复边生成边播延迟已大降，
> 但边说边打断（barge-in）要 WebSocket/WebRTC + VAD，留到后续。流式只走对话；退款类意图路由用 turn-based `/voice/chat`。

## 关联文档

- 客服场景总览 → `docs/scenarios.md`
- 工作流审批 → `docs/workflow-integration.md`
- 飞书渠道（同一大脑的文本兄弟） → `docs/workflow-integration.md`「渠道（飞书）」
- 待完善项 → `docs/roadmap.md`
