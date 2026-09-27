package com.example.lms.config;

import com.abandonware.patch.config.OnnxLimiterConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxLimiterPropertyBindingTest {
    @ParameterizedTest(name = "explicit-onnx-limiter:{0}:capacity={1}")
    @CsvSource({"absent,2", "1,1", "2,2", "0,1", "-3,1"})
    void globalConfigurationBindsCapacityAndClampsWithRealPermitAdmission(String value, int expected) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                })
                .withUserConfiguration(OnnxLimiterConfiguration.class);
        if (!"absent".equals(value)) runner = runner.withPropertyValues("zsys.onnx.max-concurrency=" + value);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("zsys.onnx.max-concurrency"))
                    .isEqualTo("absent".equals(value) ? null : value);
            assertThat(context).hasSingleBean(Semaphore.class);
            Semaphore limiter = context.getBean("onnxLimiter", Semaphore.class);
            assertThat(limiter.availablePermits()).isEqualTo(expected);
            int held = 0;
            try {
                for (int i = 0; i < expected; i++) {
                    assertThat(limiter.tryAcquire()).isTrue();
                    held++;
                }
                assertThat(limiter.availablePermits()).isZero();
                boolean extraAcquired = limiter.tryAcquire();
                if (extraAcquired) held++;
                assertThat(extraAcquired).isFalse();
            } finally {
                limiter.release(held);
            }
            assertThat(limiter.availablePermits()).isEqualTo(expected);
            System.out.printf("TBL07_GLOBAL_ONNX_LIMITER value=%s capacity=%d heldLimitEnforced=true permitsRestored=true explicitConfiguration=true inheritedEnvironmentRemoved=true%n",
                    value, expected);
        });
    }
}
