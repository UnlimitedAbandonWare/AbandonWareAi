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


    @Test
    void existingFailureDiagnosticsExposeOnlyMirrorCountersWithoutReadingEvents() {
        var store = org.mockito.Mockito.mock(com.example.lms.debug.DebugEventStore.class);
        java.util.Map<String, Object> expected = java.util.Map.of(
                "counterScope", "process_lifetime", "enabled", true,
                "acceptedCount", 3L, "writeSuccessCount", 1L,
                "writeFailureCount", 1L, "abandonedCount", 0L,
                "droppedCount", 1L, "queueDepth", 1);
        org.mockito.Mockito.when(store.ndjsonMirrorStats()).thenReturn(expected);
        var runtime = org.mockito.Mockito.mock(DebugEventsSseRuntime.class);
        var controller = new DebugEventsDiagnosticsController(store, runtime, 300_000L);
        var result = controller.apiFailures();
        assertEquals(expected, result.get("ndjsonMirrorStats"));
        assertEquals(java.util.Set.of("counterScope", "enabled", "acceptedCount",
                        "writeSuccessCount", "writeFailureCount", "abandonedCount",
                        "droppedCount", "queueDepth"),
                ((java.util.Map<?, ?>) result.get("ndjsonMirrorStats")).keySet());
        org.mockito.Mockito.verify(store).ndjsonMirrorStats();
        org.mockito.Mockito.verifyNoMoreInteractions(store);
    }

    private boolean isViewParameter(Parameter parameter) {
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        return requestParam != null
                && ("view".equals(requestParam.name()) || "view".equals(requestParam.value()));
    }
}
