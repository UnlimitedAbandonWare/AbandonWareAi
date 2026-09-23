package com.nova.protocol.config;

import com.example.lms.config.GuardConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class NovaProtocolGuardNamespaceTest {

    @Test
    void protocolGuardBeansUseProtocolNamesWhenLoadedAlone() {
        new ApplicationContextRunner()
                .withUserConfiguration(NovaProtocolConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("citationGate");
                    assertThat(context).doesNotHaveBean("piiSanitizer");
                    assertThat(context).hasBean("novaProtocolCitationGate");
                    assertThat(context).hasBean("novaProtocolPiiSanitizer");
                    assertThat(context.getBean("novaProtocolCitationGate"))
                            .isInstanceOf(com.nova.protocol.guard.CitationGate.class);
                    assertThat(context.getBean("novaProtocolPiiSanitizer"))
                            .isInstanceOf(com.nova.protocol.guard.PIISanitizer.class);
                });
    }

    @Test
    void appCitationGateKeepsCanonicalBeanNameBesideProtocolGate() {
        new ApplicationContextRunner()
                .withUserConfiguration(GuardConfig.class, NovaProtocolConfig.class)
                .run(context -> {
                    assertThat(context).hasBean("citationGate");
                    assertThat(context.getBean("citationGate"))
                            .isInstanceOf(com.example.lms.service.guard.CitationGate.class);
                    assertThat(context).hasBean("novaProtocolCitationGate");
                    assertThat(context.getBean("novaProtocolCitationGate"))
                            .isInstanceOf(com.nova.protocol.guard.CitationGate.class);
                });
    }
}
