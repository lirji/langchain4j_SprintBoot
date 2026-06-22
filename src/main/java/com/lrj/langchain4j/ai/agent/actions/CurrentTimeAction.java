package com.lrj.langchain4j.ai.agent.actions;

import com.lrj.langchain4j.ai.agent.AgentAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 示例动作：返回某时区当前时间。演示「实现 {@link AgentAction} + {@code @Component} 即被深度 Agent
 * 自动发现」——加 RAG 检索 / NL2SQL / 调外部 API 等真实能力照此办理，无需改循环。
 *
 * <p>条件化在 {@code app.deep-agent.enabled=true}：关闭时不装配（与整条深度 Agent 链一致）。
 */
@Component
@ConditionalOnProperty(name = "app.deep-agent.enabled", havingValue = "true")
public class CurrentTimeAction implements AgentAction {

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public String description() {
        return "查询当前时间；actionInput 填 IANA 时区（如 Asia/Shanghai），留空则用系统默认时区";
    }

    @Override
    public String run(String input) {
        ZoneId zone;
        try {
            zone = (input == null || input.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(input.trim());
        } catch (Exception e) {
            return "无法识别的时区 '" + input + "'，请用 IANA 格式（如 Asia/Shanghai / UTC）";
        }
        return ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " (" + zone + ")";
    }
}
