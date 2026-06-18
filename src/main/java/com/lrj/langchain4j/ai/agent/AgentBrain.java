package com.lrj.langchain4j.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 深度 Agent 的「大脑」：给定目标 + 工作记忆 + 历史 + 可用动作，决定<strong>下一步</strong>做什么
 * （ReAct 单步决策）。无 ChatMemory——循环每步把状态（scratchpad + history）显式重注入，让单步可重复、
 * 可单测。普通接口，由 {@code DeepAgentConfig} 用 {@code AiServices.builder} 程序化构建（默认关、不自动装配）。
 */
public interface AgentBrain {

    @SystemMessage("""
            你是一个自主 Agent 的决策核心，按 ReAct 模式一次只决定**下一步**。

            循环规则：
            1. 读目标、scratchpad（你之前记下的结论）、history（已执行的动作与观察）。
            2. 选**恰好一个**动作：要么是「可用动作」清单里的某个 name，要么是 finish。
            3. 信息已足够回答目标时，立即用 action=finish 并在 finalAnswer 给出面向用户的最终答案——
               不要为了用满步数而继续。
            4. note 字段用来沉淀**持久结论/计划**（会进 scratchpad 跨步保留）；临时推理放 thought 即可。
            5. 不要重复刚做过且没带来新信息的动作；动作失败时换一种入参或换一个动作。
            6. action 必须严格等于清单里的某个 name（大小写不敏感）或 finish；不要编造动作名。

            只输出结构化决策，不要寒暄。
            """)
    @UserMessage("""
            # 目标
            {{goal}}

            # 可用动作
            {{actions}}

            # scratchpad（你的工作记忆）
            {{scratchpad}}

            # history（最近的动作 → 观察）
            {{history}}

            决定下一步。
            """)
    AgentDecision decide(@V("goal") String goal,
                         @V("actions") String actions,
                         @V("scratchpad") String scratchpad,
                         @V("history") String history);
}
