package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 56 cycle-02: 세션 제목 말줄임에 주석 문법(/* ... *&#47;)이 노출되지 않는지
 * 소스 계약으로 검증한다.
 */
class ChatHistoryServiceImplSessionTitleSourceTest {

    @Test
    void sessionTitleTruncationDoesNotUseCommentSyntax() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatHistoryServiceImpl.java"));

        assertFalse(source.contains("/* ... */"),
                "visible session title must not expose comment syntax");
    }

    @Test
    void sessionTitleTruncationKeepsBoundedLengthMarker() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatHistoryServiceImpl.java"));

        long markerCount = source.lines()
                .filter(line -> line.contains("safe.substring(0, 20) + \"...\""))
                .count();
        assertTrue(markerCount >= 2,
                "both session-title paths must keep the bounded truncation marker");
    }
}
