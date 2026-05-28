package com.lrj.langchain4j.security;

import java.util.Set;

/**
 * Per-request holder for tenant / user / scopes，配合 {@code ApiKeyAuthFilter} 在请求入口注入，
 * 出口清理。{@code MultiAgentConfig.MdcCopyingTaskDecorator} 已扩展为同时透传到子线程，
 * multi-agent worker 和 eval 子任务都能拿到本租户身份。
 *
 * <p>语义上跟现有 {@link com.lrj.langchain4j.rag.CategoryContext} 一致：ThreadLocal，
 * 调用方负责 try/finally 清理。{@link #current()} 在未 set 时返回 {@link #ANONYMOUS}（仅用于
 * 兜底，例如未挂 filter 的内部调用 / 启动期初始化）。
 */
public final class TenantContext {

    /** 兜底租户：所有未挂 auth filter 的调用都视为 anonymous，方便单元测试和启动期。 */
    public static final Tenant ANONYMOUS = new Tenant("anonymous", "anonymous", Set.of());

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    /** 返回当前线程绑定的 Tenant，未设置时返回 {@link #ANONYMOUS}。 */
    public static Tenant current() {
        Tenant t = CURRENT.get();
        return t == null ? ANONYMOUS : t;
    }

    /** 用于 task decorator 跨线程拷贝时拿当前线程的原始值（可能为 null）。 */
    public static Tenant captureRaw() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Tenant(String tenantId, String userId, Set<String> scopes) {
        public boolean hasScope(String scope) {
            return scopes != null && scopes.contains(scope);
        }
    }
}
