package com.example.lms.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlDisplayUtilTest {

    @Test
    void humanRedactsSupabaseKeyShapedQueryValuesEvenWhenParameterNameIsGeneric() {
        String rawKey = "sb_secret_" + "urldisplay123456";

        String displayed = UrlDisplayUtil.human("https://example.test/callback?ref=" + rawKey + "&safe=1");

        assertFalse(displayed.contains(rawKey), displayed);
        assertFalse(displayed.contains("sb_secret_"), displayed);
        assertTrue(displayed.contains("[redacted]"), displayed);
    }

    @Test
    void decodeFailureUsesStableInvalidUrlErrorType() throws Exception {
        java.lang.reflect.Method method = UrlDisplayUtil.class.getDeclaredMethod("errorType", Throwable.class);
        method.setAccessible(true);

        assertEquals("invalid_url", method.invoke(null, new IllegalArgumentException("private-url-token")));
    }
}
