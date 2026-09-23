package com.example.lms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
class LmsApplicationContextLoadsTest {

    @Test
    void contextLoads() {
    }
}
