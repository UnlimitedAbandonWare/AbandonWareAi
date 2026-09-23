package com.example.lms.service.verification;

import com.example.lms.LmsApplication;
import com.example.lms.service.FactVerifierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("local")
@SpringBootTest(
        classes = LmsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.web-application-type=none",
                "nova.orch.enabled=true",
                "netty.enabled=false",
                "spring.task.scheduling.enabled=false"
        })
class NamedEntityValidatorWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void productionApplicationRegistersExactlyOneNamedEntityValidator() {
        Map<String, NamedEntityValidator> validators =
                applicationContext.getBeansOfType(NamedEntityValidator.class);

        assertThat(validators)
                .hasSize(1);
        assertThat(ReflectionTestUtils.getField(
                applicationContext.getBean(FactVerifierService.class),
                "namedEntityValidator"))
                .isSameAs(validators.values().iterator().next());
    }
}
