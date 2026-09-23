package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaverSearchServiceVersionTokenTest {

    @Test
    void versionTokensNormalizeWithoutCaptureGroupDependency() throws Exception {
        assertEquals("5.8", NaverSearchService.normalizeVersionToken("5.8"));
        assertEquals("5.8", NaverSearchService.normalizeVersionToken("v5.8"));
        assertEquals("5.8", NaverSearchService.normalizeVersionToken("5·8"));
        assertEquals("5.8", NaverSearchService.normalizeVersionToken("5-8"));
        assertEquals("5.8", NaverSearchService.normalizeVersionToken("5 8"));
        assertEquals("5.8.1", NaverSearchService.normalizeVersionToken("5.8.1"));

        assertEquals("5.8", extractVersionToken("원신 v5-8 업데이트"));
        assertEquals("5.8.1", extractVersionToken("Genshin 5.8.1 patch notes"));
    }

    @Test
    void versionMustRegexAcceptsSupportedSeparators() throws Exception {
        Pattern pattern = versionMustRegex("5.8.1");

        assertTrue(pattern.matcher("원신 5.8.1 업데이트").find());
        assertTrue(pattern.matcher("원신 v5·8·1 업데이트").find());
        assertTrue(pattern.matcher("Genshin 5-8-1 patch").find());
        assertTrue(pattern.matcher("Genshin 5 8 1 patch").find());
    }

    private static String extractVersionToken(String query) throws Exception {
        Method method = NaverSearchService.class.getDeclaredMethod("extractVersionToken", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, query);
    }

    private static Pattern versionMustRegex(String version) throws Exception {
        Method method = NaverSearchService.class.getDeclaredMethod("versionMustRegex", String.class);
        method.setAccessible(true);
        return (Pattern) method.invoke(null, version);
    }
}
