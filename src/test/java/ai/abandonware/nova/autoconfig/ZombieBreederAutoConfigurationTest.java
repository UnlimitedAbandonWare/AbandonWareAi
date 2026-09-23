package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.boot.exec.zombie.CleanPoolFactory;
import ai.abandonware.nova.boot.exec.zombie.DefaultZombieBreederDetector;
import ai.abandonware.nova.boot.exec.zombie.ZombieBreederContainmentAspect;
import ai.abandonware.nova.boot.exec.zombie.ZombieBreederProperties;
import ai.abandonware.nova.boot.exec.zombie.ZombieContainmentMigrator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ZombieBreederAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NovaOpsStabilizationAutoConfiguration.class))
            .withPropertyValues(
                    "nova.orch.interrupt-hygiene.cancel-shield.enabled=false",
                    "nova.orch.debug.reactor-onErrorDropped.enabled=false",
                    "nova.orch.embedding.matryoshka-shield.enabled=false",
                    "nova.orch.web.failsoft.hybrid-empty-fallback.enabled=false",
                    "nova.orch.web.failsoft.ratelimit-backoff.enabled=false");

    @Test
    void disabledDoesNotRegisterZombieBeans() {
        contextRunner
                .withPropertyValues("nova.zombie.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ZombieBreederProperties.class);
                    assertThat(context).doesNotHaveBean(DefaultZombieBreederDetector.class);
                    assertThat(context).doesNotHaveBean(CleanPoolFactory.class);
                    assertThat(context).doesNotHaveBean(ZombieContainmentMigrator.class);
                    assertThat(context).doesNotHaveBean(ZombieBreederContainmentAspect.class);
                });
    }

    @Test
    void enabledRegistersZombieBeansAndBindsDynamicProperties() {
        contextRunner
                .withPropertyValues(
                        "nova.zombie.enabled=true",
                        "nova.zombie.max-containment-pool-size=3",
                        "nova.zombie.detection-window-ms=12000")
                .run(context -> {
                    assertThat(context).hasSingleBean(ZombieBreederProperties.class);
                    assertThat(context).hasSingleBean(DefaultZombieBreederDetector.class);
                    assertThat(context).hasSingleBean(CleanPoolFactory.class);
                    assertThat(context).hasSingleBean(ZombieContainmentMigrator.class);
                    assertThat(context).hasSingleBean(ZombieBreederContainmentAspect.class);
                    ZombieBreederProperties props = context.getBean(ZombieBreederProperties.class);
                    assertThat(props.getMaxContainmentPoolSize()).isEqualTo(3);
                    assertThat(props.getDetectionWindowMs()).isEqualTo(12_000L);
                });
    }
}
