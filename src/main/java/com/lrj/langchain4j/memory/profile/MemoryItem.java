package com.lrj.langchain4j.memory.profile;

/**
 * 一条<strong>跨会话</strong>的长期记忆（用户画像项）。区别于会话内滑窗记忆（{@code ChatMemory}，
 * 按 chatId 保留最近若干轮）—— 这里存的是「关于这个用户、值得跨会话长期记住」的事实：
 * 偏好 / 稳定属性 / 反复出现的诉求。
 *
 * @param id            稳定 id（归一文本的哈希，便于去重/删除）
 * @param text          记忆内容（一句话事实，如「偏好邮件联系」）
 * @param type          分类：preference | attribute | issue | other（仅用于分组展示，不强约束）
 * @param createdAtEpochMs 写入时间（毫秒），用于 cap 淘汰最旧
 * @param sourceChatId  来源会话（审计/溯源）
 */
public record MemoryItem(String id, String text, String type, long createdAtEpochMs, String sourceChatId) {
}
