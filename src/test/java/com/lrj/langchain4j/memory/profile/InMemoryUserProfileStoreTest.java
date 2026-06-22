package com.lrj.langchain4j.memory.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 长期记忆存储确定性单测：去重 / 容量上限 / 租户·用户隔离 / 清空。 */
class InMemoryUserProfileStoreTest {

    private static MemoryItem item(String id, String text) {
        return new MemoryItem(id, text, "preference", System.currentTimeMillis(), "c1");
    }

    @Test
    void addAndList_isolatedByTenantAndUser() {
        InMemoryUserProfileStore s = new InMemoryUserProfileStore(50);
        s.add("t1", "alice", List.of(item("1", "偏好邮件联系")));
        s.add("t1", "bob", List.of(item("2", "偏好电话联系")));
        s.add("t2", "alice", List.of(item("3", "企业版客户")));

        assertThat(s.list("t1", "alice")).extracting(MemoryItem::text).containsExactly("偏好邮件联系");
        assertThat(s.list("t1", "bob")).extracting(MemoryItem::text).containsExactly("偏好电话联系");
        assertThat(s.list("t2", "alice")).extracting(MemoryItem::text).containsExactly("企业版客户");
    }

    @Test
    void dedup_nearDuplicateBySubstring() {
        // v1 去重是 lexical（归一相等或互为子串）：'偏好邮件' 是 '偏好邮件联系' 的子串 → 判重。
        // 语义近似但非子串（'偏好通过邮件联系'）不会被这条规则捕获——那要 embedding 消歧（后续）。
        InMemoryUserProfileStore s = new InMemoryUserProfileStore(50);
        s.add("t1", "u", List.of(item("1", "偏好邮件联系")));
        int added = s.add("t1", "u", List.of(item("2", "偏好邮件")));   // 子串 → 视为重复
        assertThat(added).isZero();
        assertThat(s.list("t1", "u")).hasSize(1);
    }

    @Test
    void cap_evictsOldest() {
        InMemoryUserProfileStore s = new InMemoryUserProfileStore(2);
        s.add("t1", "u", List.of(item("1", "事实一")));
        s.add("t1", "u", List.of(item("2", "事实二")));
        s.add("t1", "u", List.of(item("3", "事实三")));   // 超上限 → 淘汰最旧
        assertThat(s.list("t1", "u")).extracting(MemoryItem::text).containsExactly("事实二", "事实三");
    }

    @Test
    void blankText_skipped() {
        InMemoryUserProfileStore s = new InMemoryUserProfileStore(50);
        assertThat(s.add("t1", "u", List.of(item("1", "   ")))).isZero();
    }

    @Test
    void clear_removesOnlyThatUser() {
        InMemoryUserProfileStore s = new InMemoryUserProfileStore(50);
        s.add("t1", "u", List.of(item("1", "事实一"), item("2", "事实二")));
        s.add("t1", "v", List.of(item("3", "另一人")));
        assertThat(s.clear("t1", "u")).isEqualTo(2);
        assertThat(s.list("t1", "u")).isEmpty();
        assertThat(s.list("t1", "v")).hasSize(1);
    }
}
