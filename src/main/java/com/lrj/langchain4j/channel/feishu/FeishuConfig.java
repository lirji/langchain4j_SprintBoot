package com.lrj.langchain4j.channel.feishu;

import com.lrj.langchain4j.config.MultiAgentConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 飞书渠道装配（M1.B）。整套 {@code @ConditionalOnProperty(app.channel.feishu.enabled)}，默认关 = 零开销。
 *
 * <p>{@code feishuExecutor}：入站处理（chat / workflow start，含 LLM 调用，常 >5s）放这个池跑，让
 * {@code FeishuController} 立刻返回 200 满足飞书 ~5s ack 要求；完成后主动回推。复用
 * {@link MultiAgentConfig.MdcCopyingTaskDecorator} 把 traceId 透传到 worker 线程，日志可串联。
 * {@code FeishuReplyListener} 的 {@code @Async("feishuExecutor")} 也用它。
 */
@Configuration
@ConditionalOnProperty(name = "app.channel.feishu.enabled", havingValue = "true")
@EnableConfigurationProperties(FeishuProperties.class)
public class FeishuConfig {

    @Bean
    public Executor feishuExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("feishu-");
        exec.setTaskDecorator(new MultiAgentConfig.MdcCopyingTaskDecorator());
        exec.initialize();
        return exec;
    }
}
