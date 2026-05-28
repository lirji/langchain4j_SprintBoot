package com.lrj.langchain4j.config;

import dev.langchain4j.spi.classloading.ClassInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/**
 * Spring-aware 的 LangChain4j {@link ClassInstanceFactory} SPI 实现。
 *
 * <p><b>为什么需要它</b>：LangChain4j 实例化 {@code @InputGuardrails(X.class)} /
 * {@code @OutputGuardrails(Y.class)} 引用的类时，走 {@code ClassInstanceLoader.getClassInstance}，
 * 默认是反射调<strong>无参构造</strong>。本项目的 guardrail（{@code PromptInjectionGuardrail} 需
 * {@code PromptInjectionDetector}、{@code PiiGuardrail} 需 {@code AuditLogger}）只有<strong>带参构造</strong>，
 * 反射无参实例化会抛 {@code NoSuchMethodException}，导致主 {@code Assistant.chat} 整条挂掉。
 *
 * <p>{@code ClassInstanceLoader} 先 {@code ServiceLoader.load(ClassInstanceFactory.class).findFirst()}，
 * 命中就用 SPI 解析，否则才回退反射。本类注册在
 * {@code META-INF/services/dev.langchain4j.spi.classloading.ClassInstanceFactory}，于是 LangChain4j
 * 所有 class 实例化先尝试从 Spring 容器取 bean —— guardrail 拿到的就是被正确依赖注入的单例。
 *
 * <p>非 Spring bean 的类（LangChain4j 内部类等）取不到 bean 时回退反射无参，行为与默认一致，无回归。
 *
 * <p>本类由 {@code ServiceLoader} 实例化，必须有 public 无参构造；context 通过
 * {@link SpringContextHolder} 静态获取。guardrail 实例化是惰性的（首次调用 AiService 方法时才建，
 * 且结果被缓存），那时 context 早已就绪。
 */
public class SpringClassInstanceFactory implements ClassInstanceFactory {

    private static final Logger log = LoggerFactory.getLogger(SpringClassInstanceFactory.class);

    @Override
    public <T> T getInstanceOfClass(Class<T> clazz) {
        ApplicationContext ctx = SpringContextHolder.getApplicationContext();
        if (ctx != null) {
            try {
                T bean = ctx.getBeanProvider(clazz).getIfAvailable();
                if (bean != null) {
                    return bean;
                }
            } catch (RuntimeException e) {
                // 多个候选 bean / 解析异常 —— 回退反射，不让它阻断
                log.debug("Spring bean lookup failed for {}, falling back to reflection", clazz.getName(), e);
            }
        }
        return createByReflection(clazz);
    }

    private static <T> T createByReflection(Class<T> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Cannot instantiate " + clazz.getName()
                    + ": no Spring bean of this type and no usable no-arg constructor", e);
        }
    }
}
