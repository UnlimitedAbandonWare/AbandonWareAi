package com.example.lms.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class InputDistillationServiceTest {

    @Test
    void negativeMaxPriorCharsDoesNotThrow() {
        InputDistillationService service = new InputDistillationService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxPriorChars", -1);

        assertThat(service.distillForAugment("previous answer")).isEmpty();
    }
}
