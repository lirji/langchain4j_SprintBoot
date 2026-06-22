package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.memory.profile.MemoryItem;
import com.lrj.langchain4j.memory.profile.UserProfileChatService;
import com.lrj.langchain4j.memory.profile.UserProfileService;
import com.lrj.langchain4j.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆 / 用户画像入口（{@code app.memory.profile.enabled=true} 才映射）。走 {@code /chat} 同款鉴权链
 * （{@code X-Api-Key} → tenant + user）。记忆按 (tenant, user) 隔离。
 */
@RestController
@ConditionalOnProperty(name = "app.memory.profile.enabled", havingValue = "true")
public class MemoryController {

    private final UserProfileChatService memoryChat;
    private final UserProfileService profileService;

    public MemoryController(UserProfileChatService memoryChat, UserProfileService profileService) {
        this.memoryChat = memoryChat;
        this.profileService = profileService;
    }

    /** 记忆增强对话：chat 前注入该用户长期记忆、chat 后异步更新画像。 */
    @PostMapping("/chat/memory")
    public Map<String, String> chat(@RequestParam(defaultValue = "default") String chatId,
                                    @RequestBody Map<String, String> body) {
        return Map.of("reply", memoryChat.chat(chatId, body.getOrDefault("message", "")));
    }

    /** 查看当前用户的长期记忆（透明可审）。 */
    @GetMapping("/memory/profile")
    public Map<String, Object> profile() {
        List<MemoryItem> items = profileService.list(
                TenantContext.current().tenantId(), TenantContext.current().userId());
        return Map.of("count", items.size(), "items", items);
    }

    /** PII 合规删除：清空当前用户的长期记忆。 */
    @DeleteMapping("/memory/profile")
    public Map<String, Object> clear() {
        int removed = profileService.clear(
                TenantContext.current().tenantId(), TenantContext.current().userId());
        return Map.of("removed", removed);
    }
}
