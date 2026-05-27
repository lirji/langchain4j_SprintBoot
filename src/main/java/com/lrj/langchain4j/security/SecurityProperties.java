package com.lrj.langchain4j.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code app.security.*} 配置。MVP 阶段用静态 map 把 api-key 映射到 (tenant, user, scopes)；
 * 生产可以换成 DB 表 / Redis hash，把 {@code ApiKeyAuthFilter} 里的查找替换掉即可。
 *
 * <pre>
 * app.security:
 *   enabled: true
 *   api-keys:
 *     dev-key-tenantA-admin:
 *       tenant: tenantA
 *       user: alice
 *       scopes: [chat, ingest, eval]
 *     dev-key-tenantB-readonly:
 *       tenant: tenantB
 *       user: bob
 *       scopes: [chat]
 * </pre>
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** 关掉就完全跳过 auth filter（本地 demo 用）。默认开。 */
    private boolean enabled = true;

    /** api-key -> 主体绑定。 */
    private Map<String, KeyBinding> apiKeys = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, KeyBinding> getApiKeys() { return apiKeys; }
    public void setApiKeys(Map<String, KeyBinding> apiKeys) { this.apiKeys = apiKeys; }

    public static class KeyBinding {
        private String tenant;
        private String user;
        private List<String> scopes = List.of();

        public String getTenant() { return tenant; }
        public void setTenant(String tenant) { this.tenant = tenant; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
    }
}
