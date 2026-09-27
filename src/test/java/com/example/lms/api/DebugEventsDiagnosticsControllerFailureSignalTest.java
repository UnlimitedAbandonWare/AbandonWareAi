package com.example.lms.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DebugEventsDiagnosticsControllerFailureSignalTest {

    @Test
    void streamContractDoesNotAddAnUnapprovedFailureSignalView() {
        Method stream = Arrays.stream(DebugEventsDiagnosticsController.class.getDeclaredMethods())
                .filter(method -> "stream".equals(method.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(4, stream.getParameterCount());
        assertFalse(Arrays.stream(stream.getParameters()).anyMatch(this::isViewParameter));
    }

    private boolean isViewParameter(Parameter parameter) {
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        return requestParam != null
                && ("view".equals(requestParam.name()) || "view".equals(requestParam.value()));
    }
}
