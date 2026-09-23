package com.example.lms.infra.resilience;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightmareBreakerLegacyRemovalTest {

    @Test
    void removesRetiredStateRuntimePathAndPreservesCompatibilityAdapters() throws Exception {
        assertFalse(Arrays.stream(NightmareBreaker.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("State")));
        assertFalse(Arrays.stream(NightmareBreaker.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("legacyStates")));
        assertFalse(Arrays.stream(NightmareBreaker.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("legacyStatePathAllowed")));

        assertCompatibilityAdapter("checkOpenOrThrow", String.class);
        assertCompatibilityAdapter("recordSuccess", String.class, long.class);
        assertCompatibilityAdapter("recordBlank", String.class, String.class);
        assertCompatibilityAdapter("recordSilentFailure", String.class, String.class, String.class);
        assertCompatibilityAdapter("recordFailure", String.class, NightmareBreaker.FailureKind.class,
                Throwable.class, String.class);
        assertCompatibilityAdapter("recordFailure", String.class, NightmareBreaker.FailureKind.class,
                Throwable.class, String.class, Long.class);
        assertCompatibilityAdapter("recordRateLimit", String.class, String.class, String.class);
        assertCompatibilityAdapter("recordRateLimit", String.class, String.class, String.class, Long.class);
        assertCompatibilityAdapter("recordRateLimit", String.class, String.class, Throwable.class,
                String.class, Long.class);
        assertCompatibilityAdapter("recordRejected", String.class, String.class, String.class);
        assertCompatibilityAdapter("recordTimeout", String.class, String.class, String.class);
    }

    private static void assertCompatibilityAdapter(String name, Class<?>... parameterTypes) throws Exception {
        Method method = NightmareBreaker.class.getDeclaredMethod(name, parameterTypes);

        assertTrue(Modifier.isPublic(method.getModifiers()), name + " must remain public");
        assertEquals(void.class, method.getReturnType(), name + " must return void");
        Deprecated deprecated = method.getAnnotation(Deprecated.class);
        assertNotNull(deprecated, name + " must remain deprecated");
        assertFalse(deprecated.forRemoval(), name + " must not be marked for removal");
    }
}
