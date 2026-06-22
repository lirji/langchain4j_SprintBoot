package com.lrj.langchain4j.memory.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** UserProfileService 确定性单测（stub 抽取器，同步 executor，不连模型）：召回格式 / 观察入库 / 容错。 */
class UserProfileServiceTest {

    private static UserProfileService svc(UserProfileStore store, ProfileExtractor extractor, int recallLimit) {
        // async=false + 直接执行器 → 确定性（observe 同步完成）
        return new UserProfileService(store, extractor, Runnable::run, false, recallLimit);
    }

    private static ProfileExtractor stub(MemoryFact... facts) {
        return (userMessage, assistantReply) -> new ExtractedMemories(List.of(facts));
    }

    @Test
    void recall_emptyWhenNoMemories() {
        UserProfileService s = svc(new InMemoryUserProfileStore(50), stub(), 12);
        assertThat(s.recall("t1", "u")).isEmpty();
    }

    @Test
    void observe_extractsAndStores_recallRendersBullets() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        UserProfileService s = svc(store, stub(
                new MemoryFact("偏好邮件联系", "preference"),
                new MemoryFact("企业版客户", "attribute")), 12);

        s.observe("t1", "u", "c1", "以后发我邮箱", "好的");
        assertThat(store.list("t1", "u")).hasSize(2);
        assertThat(s.recall("t1", "u")).isEqualTo("- 偏好邮件联系\n- 企业版客户");
    }

    @Test
    void recall_capsToRecallLimit_mostRecent() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        UserProfileService s = svc(store, stub(), 2);
        store.add("t1", "u", List.of(
                new MemoryItem("1", "事实一", "o", 1, "c"),
                new MemoryItem("2", "事实二", "o", 2, "c"),
                new MemoryItem("3", "事实三", "o", 3, "c")));
        assertThat(s.recall("t1", "u")).isEqualTo("- 事实二\n- 事实三");   // 最近 2 条
    }

    @Test
    void observe_emptyExtraction_noOp() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        UserProfileService s = svc(store, stub(), 12);   // 抽不出 → 空
        s.observe("t1", "u", "c1", "今天几号", "6 月 17 号");
        assertThat(store.list("t1", "u")).isEmpty();
    }

    @Test
    void observe_extractorThrows_swallowed() {
        InMemoryUserProfileStore store = new InMemoryUserProfileStore(50);
        ProfileExtractor boom = (u, a) -> { throw new RuntimeException("llm down"); };
        UserProfileService s = svc(store, boom, 12);
        s.observe("t1", "u", "c1", "x", "y");            // 不抛
        assertThat(store.list("t1", "u")).isEmpty();
    }
}
