package com.example.lms.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BootSmokeIT {
    @Test
    void contextLoads() {
        // Context boot only; PASS if no exceptions.
    }
}
