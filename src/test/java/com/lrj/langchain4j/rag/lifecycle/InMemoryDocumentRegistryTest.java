package com.lrj.langchain4j.rag.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 DocumentRegistry 接口语义（put/get/list/remove + per-tenant 隔离）。
 * RedisDocumentRegistry 走同一接口契约，靠端到端验证覆盖（需真 Redis）。
 */
class InMemoryDocumentRegistryTest {

    private final DocumentRegistry registry = new InMemoryDocumentRegistry();

    private static DocumentInfo doc(String tenant, String docId, String name) {
        return new DocumentInfo(docId, tenant, name, "application/pdf", 100, 1, 1, Instant.now(), null);
    }

    @Test
    void putGetListRemove() {
        registry.put(doc("tenantA", "d1", "a.pdf"));
        assertThat(registry.get("tenantA", "d1")).isPresent()
                .get().extracting(DocumentInfo::displayName).isEqualTo("a.pdf");
        assertThat(registry.list("tenantA")).hasSize(1);

        assertThat(registry.remove("tenantA", "d1")).isPresent();
        assertThat(registry.get("tenantA", "d1")).isEmpty();
        assertThat(registry.list("tenantA")).isEmpty();
    }

    @Test
    void tenantsAreIsolated() {
        registry.put(doc("tenantA", "d1", "a.pdf"));
        registry.put(doc("tenantB", "d2", "b.pdf"));

        assertThat(registry.list("tenantA")).hasSize(1);
        assertThat(registry.get("tenantB", "d1")).isEmpty();      // A 的 docId 在 B 下查不到
        assertThat(registry.list("tenantB")).extracting(DocumentInfo::displayName).containsExactly("b.pdf");
    }

    @Test
    void unknownReturnsEmpty() {
        assertThat(registry.get("nobody", "nope")).isEmpty();
        assertThat(registry.list("nobody")).isEmpty();
        assertThat(registry.remove("nobody", "nope")).isEmpty();
    }
}
