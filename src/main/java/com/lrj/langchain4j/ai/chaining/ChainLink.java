package com.lrj.langchain4j.ai.chaining;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Prompt Chaining 的单个链节：按 {@code instruction} 处理 {@code input}，输出交给下一节。
 *
 * <p>Anthropic《Building Effective Agents》里 <b>Prompt Chaining</b> 模式的执行单元——把一个大任务
 * 拆成固定顺序的若干步，每步一次 LLM 调用、只处理上一步的输出（预定义代码路径，非模型自主决定流程）。
 * 无 ChatMemory / RAG，纯 transform，便于确定性编排与单测。由 {@code ChainingConfig} 程序化构建、走主
 * {@code ChatModel}（token 自动纳入配额），不新建 ChatModel Bean。
 */
public interface ChainLink {

    @SystemMessage("""
            你是链式处理流水线里的一个节点。严格按「指令」处理「输入」，只输出处理结果本身——
            不要解释你做了什么、不要寒暄、不要复述指令。
            """)
    @UserMessage("""
            指令：{{instruction}}

            输入：
            {{input}}
            """)
    String transform(@V("instruction") String instruction, @V("input") String input);
}
