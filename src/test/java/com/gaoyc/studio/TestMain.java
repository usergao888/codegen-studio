package com.gaoyc.studio;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 离线环境的测试入口。
 *
 * <p>这台机器的 ~/.m2 里缺 maven-surefire-plugin 的依赖链，{@code mvn test} 跑不起来，
 * 所以用一个 40 行的反射运行器直接调 JUnit 注解的方法 —— 断言仍是 JUnit 的，
 * 换到有网的机器上 {@code mvn test} 一样能跑。
 */
public final class TestMain {

    private static final List<Class<?>> SUITES = List.of(
            com.gaoyc.studio.spec.SpecParserTest.class,
            com.gaoyc.studio.gen.SiteGeneratorTest.class,
            com.gaoyc.studio.gen.MultiFileLayoutTest.class,
            com.gaoyc.studio.qa.QaGateTest.class,
            com.gaoyc.studio.repair.RepairerTest.class,
            com.gaoyc.studio.cache.TwoLevelCacheTest.class,
            com.gaoyc.studio.cache.InMemoryL2Test.class,
            com.gaoyc.studio.limit.TokenBucketRateLimiterTest.class,
            com.gaoyc.studio.limit.MultiLevelRateLimiterTest.class,
            com.gaoyc.studio.llm.ModelRouterTest.class,
            com.gaoyc.studio.llm.LlmSiteWriterTest.class,
            com.gaoyc.studio.repository.JsonFileRunRepositoryTest.class,
            com.gaoyc.studio.service.ApprovalServiceTest.class,
            com.gaoyc.studio.service.GenerationServiceCacheKeyTest.class,
            com.gaoyc.studio.auth.ApiKeyAuthInterceptorTest.class,
            com.gaoyc.studio.publish.SitePublisherTest.class
    );

    private TestMain() {
    }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        List<String> failures = new ArrayList<>();

        for (Class<?> suite : SUITES) {
            Object instance = suite.getDeclaredConstructor().newInstance();
            for (Method method : suite.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Test.class)) {
                    continue;
                }
                method.setAccessible(true);
                String name = suite.getSimpleName() + "." + method.getName();
                try {
                    method.invoke(instance);
                    passed++;
                    System.out.println("  PASS  " + name);
                } catch (InvocationTargetException e) {
                    failures.add(name + " -> " + e.getCause());
                    System.out.println("  FAIL  " + name + " -> " + e.getCause());
                }
            }
        }

        System.out.printf("%ntests: %d passed, %d failed%n", passed, failures.size());
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}
