package com.example.lms.config;

import com.abandonware.patch.config.PatchAutoConfiguration;
import com.abandonware.patch.guard.FinalSigmoidGate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PatchFinalSigmoidPropertyBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PatchAutoConfiguration.class);

    @ParameterizedTest(name = "explicit-config-disabled:{0}")
    @ValueSource(strings = {"absent", "false"})
    void explicitConfigurationRequiresGlobalEnableFlag(String enabled) {
        ApplicationContextRunner selected = runner.withPropertyValues(
                "gate.finalSigmoid.k=6.0", "gate.finalSigmoid.x0=0.1");
        if ("false".equals(enabled)) selected = selected.withPropertyValues("gate.finalSigmoid.enabled=false");
        selected.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FinalSigmoidGate.class);
            System.out.printf("TBL07_GLOBAL_SIGMOID enabled=%s beanPresent=false explicitConfiguration=true%n", enabled);
        });
    }

    static Stream<Arguments> enabledConfigurations() {
        return Stream.of(
                Arguments.of("defaults", new String[0], 12.0, 0.0, true),
                Arguments.of("k-only", new String[]{"gate.finalSigmoid.k=6.0"}, 6.0, 0.0, false),
                Arguments.of("x0-only", new String[]{"gate.finalSigmoid.x0=0.1"}, 12.0, 0.1, false),
                Arguments.of("both", new String[]{"gate.finalSigmoid.k=6.0", "gate.finalSigmoid.x0=0.1"}, 6.0, 0.1, false));
    }

    @ParameterizedTest(name = "explicit-config-enabled:{0}")
    @MethodSource("enabledConfigurations")
    void explicitConfigurationBindsOnlyGlobalParameters(String scenario, String[] properties,
                                                        double k, double x0, boolean expectedAtPointTwo) {
        runner.withPropertyValues("gate.finalSigmoid.enabled=true").withPropertyValues(properties).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FinalSigmoidGate.class);
            FinalSigmoidGate gate = context.getBean(FinalSigmoidGate.class);
            assertThat(ReflectionTestUtils.getField(gate, "k")).isEqualTo(k);
            assertThat(ReflectionTestUtils.getField(gate, "x0")).isEqualTo(x0);
            assertThat(gate.pass(0.0)).isFalse();
            assertThat(gate.pass(0.2)).isEqualTo(expectedAtPointTwo);
            assertThat(gate.pass(1.0)).isTrue();
            System.out.printf("TBL07_GLOBAL_SIGMOID scenario=%s beanPresent=true exactParameters=true passAtPointTwo=%s explicitConfiguration=true%n",
                    scenario, expectedAtPointTwo);
        });
    }
}
