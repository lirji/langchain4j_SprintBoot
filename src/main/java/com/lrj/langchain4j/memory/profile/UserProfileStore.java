package com.lrj.langchain4j.memory.profile;

import java.util.List;

/**
 * 长期记忆存储，按 (tenant, user) 隔离。默认 {@link InMemoryUserProfileStore}（零依赖、重启即丢）；
 * 持久化（Redis，复用现有 {@code spring.data.redis}）作为升级路径——接口对两者都够用。
 */
public interface UserProfileStore {

    /** 合并写入（带去重 + 容量上限淘汰最旧）。返回实际新增条数。 */
    int add(String tenant, String user, List<MemoryItem> items);

    /** 列出该用户全部记忆（按写入时间升序）。 */
    List<MemoryItem> list(String tenant, String user);

    /** 清空该用户的长期记忆（PII 合规删除）。返回删除条数。 */
    int clear(String tenant, String user);
}
