package com.example.lms.service.search;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaverCredentialResourceContractTest {

    @Test
    void activeApplicationResourcesKeepClientPairBridgeFallback() throws IOException {
        assertNaverBridge("main/resources/application.yml");
        assertNaverBridge("app/src/main/resources/application.yaml");
    }

    private static void assertNaverBridge(String path) throws IOException {
        String block = topLevelBlock(Files.readString(Path.of(path)), "naver");

        assertTrue(block.contains("  keys: \"${NAVER_KEYS:}\""),
                () -> path + " must map NAVER_KEYS without self-referencing naver.keys");
        assertTrue(block.contains("  client-id: \"${NAVER_CLIENT_ID:}\""),
                () -> path + " must map NAVER_CLIENT_ID without self-referencing naver.client-id");
        assertTrue(block.contains("  client-secret: \"${NAVER_CLIENT_SECRET:}\""),
                () -> path + " must map NAVER_CLIENT_SECRET without self-referencing naver.client-secret");
        assertFalse(block.contains("${naver.keys:"),
                () -> path + " must not create a circular naver.keys placeholder");
        assertFalse(block.contains("${naver.client-id:"),
                () -> path + " must not create a circular naver.client-id placeholder");
        assertFalse(block.contains("${naver.client-secret:"),
                () -> path + " must not create a circular naver.client-secret placeholder");
    }

    private static String topLevelBlock(String source, String key) {
        StringBuilder block = new StringBuilder();
        boolean inBlock = false;
        for (String line : source.split("\\R", -1)) {
            if (line.equals(key + ":")) {
                inBlock = true;
            } else if (inBlock && !line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                break;
            }
            if (inBlock) {
                block.append(line).append('\n');
            }
        }
        return block.toString();
    }
}
