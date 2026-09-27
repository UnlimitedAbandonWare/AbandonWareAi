package com.abandonware.ai.agent.integrations;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrieverStrictLocalContractTest {

    @Test
    void strictLocalMethodCannotReachWebOrEnvironmentSelectedRemoteRerankers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/integrations/HybridRetriever.java"),
                StandardCharsets.UTF_8);
        String method = methodBody(source, "retrieveStrictLocal");

        assertTrue(method.contains("index.search"), method);
        assertTrue(method.contains("mmr("), method);
        assertFalse(method.contains("tavily"), method);
        assertFalse(method.contains("useRrf"), method);
        assertFalse(method.contains("applySecondPass"), method);
        assertFalse(method.contains("System.getenv"), method);
        assertFalse(method.contains("Remote"), method);
    }

    private static String methodBody(String source, String methodName) {
        int name = source.indexOf(methodName + "(");
        assertTrue(name >= 0, "missing method " + methodName);
        int open = source.indexOf('{', name);
        assertTrue(open >= 0, "missing method body " + methodName);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("unterminated method " + methodName);
    }
}
