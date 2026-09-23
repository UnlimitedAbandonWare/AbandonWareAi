package ai.abandonware.nova.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UawAutolearnAspectActivationConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void conditionsAreMutuallyExclusiveAndDefaultToPreferredIdlePipeline() {
        assertActivation(false, false);
        assertActivation(false, true,
                "uaw.autolearn.strict.enabled=true");
        assertActivation(true, false,
                "uaw.autolearn.strict.enabled=true",
                "uaw.autolearn.pipeline.enabled=false");
        assertActivation(false, true,
                "uaw.autolearn.strict.enabled=true",
                "uaw.autolearn.pipeline.enabled=true");
    }

    private void assertActivation(boolean strictExpected, boolean idleExpected, String... properties) {
        contextRunner.withPropertyValues(properties).run(context -> {
            Environment environment = context.getEnvironment();
            assertEquals(strictExpected,
                    matches("uawAutolearnStrictRequestAspect", environment),
                    "legacy strict activation mismatch for " + Arrays.toString(properties));
            assertEquals(idleExpected,
                    matches("uawIdleAutoTrainingPipelineAspect", environment),
                    "preferred idle activation mismatch for " + Arrays.toString(properties));
        });
    }

    private static boolean matches(String beanMethodName, Environment environment) {
        Method beanMethod = Arrays.stream(NovaOrchestrationAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(beanMethodName))
                .findFirst()
                .orElseThrow();
        ConditionalOnExpression condition = beanMethod.getAnnotation(ConditionalOnExpression.class);
        assertNotNull(condition, beanMethodName + " must retain an explicit expression gate");
        String resolvedExpression = environment.resolvePlaceholders(condition.value());
        Boolean result = new SpelExpressionParser()
                .parseExpression(resolvedExpression)
                .getValue(Boolean.class);
        return Boolean.TRUE.equals(result);
    }
}
