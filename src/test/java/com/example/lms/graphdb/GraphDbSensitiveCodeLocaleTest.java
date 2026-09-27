package com.example.lms.graphdb;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphDbSensitiveCodeLocaleTest {

    @Test
    void graphDbClientRedactsAuthorizationCodeUnderTurkishLocale() throws Exception {
        assertAuthorizationCodeIsRedacted(GraphDbClient.class);
    }

    @Test
    void manualLearningRedactsAuthorizationCodeUnderTurkishLocale() throws Exception {
        assertAuthorizationCodeIsRedacted(GraphDbManualLearningService.class);
    }

    @Test
    void graphDbClientClassifiesInterruptedCodeAsCancelledUnderTurkishLocale() throws Exception {
        Method safeFailureClass = GraphDbClient.class.getDeclaredMethod("safeFailureClass", Object.class);
        safeFailureClass.setAccessible(true);

        assertEquals("cancelled", invokeUnderTurkishLocale(
                () -> safeFailureClass.invoke(null, "INTERRUPTED")));
    }

    @Test
    void manualLearningClassifiesInterruptedExceptionAsCancelledUnderTurkishLocale() throws Exception {
        Method failureClass = GraphDbManualLearningService.class.getDeclaredMethod("failureClass", Exception.class);
        failureClass.setAccessible(true);

        assertEquals("cancelled", invokeUnderTurkishLocale(
                () -> failureClass.invoke(null, new InterruptedGraphException())));
    }

    @Test
    void manualLearningClassifiesInterruptedMessageAsCancelledUnderTurkishLocale() throws Exception {
        Method failureClass = GraphDbManualLearningService.class.getDeclaredMethod("failureClass", Exception.class);
        failureClass.setAccessible(true);

        assertEquals("cancelled", invokeUnderTurkishLocale(
                () -> failureClass.invoke(null, new RuntimeException("INTERRUPTED"))));
    }

    private static void assertAuthorizationCodeIsRedacted(Class<?> owner) throws Exception {
        Method safeCode = owner.getDeclaredMethod("safeCode", Object.class);
        safeCode.setAccessible(true);
        assertEquals("redacted", invokeUnderTurkishLocale(
                () -> safeCode.invoke(null, "AUTHORIZATION")));
    }

    private static Object invokeUnderTurkishLocale(ThrowingSupplier invocation) throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            return invocation.get();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }

    private static final class InterruptedGraphException extends Exception {
    }
}
