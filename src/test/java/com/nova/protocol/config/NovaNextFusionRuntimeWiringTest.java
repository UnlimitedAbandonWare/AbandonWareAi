package com.nova.protocol.config;

import com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser;
import com.nova.protocol.fusion.CvarAggregator;
import com.nova.protocol.fusion.NovaNextFusionService;
import com.nova.protocol.properties.NovaNextProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class NovaNextFusionRuntimeWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(NovaProtocolConfig.class)
            .withBean(WeightedReciprocalRankFuser.class, () -> new WeightedReciprocalRankFuser(60, null, ""));

    @Test
    void enabledRegistersFusionServiceAndInjectsFuser() {
        contextRunner
                .withPropertyValues("nova.next.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(NovaNextProperties.class);
                    assertThat(context).hasSingleBean(CvarAggregator.class);
                    assertThat(context).hasSingleBean(NovaNextFusionService.class);
                    Object cvar = ReflectionTestUtils.getField(
                            context.getBean(NovaNextFusionService.class),
                            "cvarAggregator");
                    assertThat(cvar).isSameAs(context.getBean(CvarAggregator.class));
                    Object injected = ReflectionTestUtils.getField(
                            context.getBean(WeightedReciprocalRankFuser.class),
                            "novaNextFusionService");
                    assertThat(injected).isSameAs(context.getBean(NovaNextFusionService.class));
                });
    }

    @Test
    void disabledDoesNotRegisterFusionService() {
        contextRunner
                .withPropertyValues("nova.next.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(NovaNextProperties.class);
                    assertThat(context).doesNotHaveBean(NovaNextFusionService.class);
                    Object injected = ReflectionTestUtils.getField(
                            context.getBean(WeightedReciprocalRankFuser.class),
                            "novaNextFusionService");
                    assertThat(injected).isNull();
                });
    }

    @Test
    void missingPropertyDoesNotRegisterFusionService() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NovaNextProperties.class);
            assertThat(context).doesNotHaveBean(NovaNextFusionService.class);
        });
    }
}
