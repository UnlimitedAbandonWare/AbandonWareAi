package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.lms.search.TraceStore;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ChatHistoryServiceImplRedactionContractTest {

    @Test
    void historyLogsDoNotUseRawThrowableMessagesOrSessionIds() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/ChatHistoryServiceImpl.java"));

        assertFalse(source.contains("e.getMessage()"));
        assertFalse(source.contains("e.toString()"));
        assertFalse(source.contains("log.debug(\"[History] saveReturning: session not found (id={})\", sessionId);"));
        assertFalse(source.contains("log.debug(\"[History] saveReturning failed (sessionId={}): {}\", sessionId,"));
        assertFalse(source.contains("skip persist (session {})\", role, sessionId"));
        assertFalse(source.contains("skip persist (session {})\", sessionId"));
        assertFalse(source.contains("Unsupported role '{}'"));
        assertFalse(source.contains("세션 {}: system meta 저장 ({} bytes)\", sessionId"));
        assertFalse(source.contains("세션 {}: {} 메시지 저장\", sessionId"));
        assertFalse(source.contains("getSessionWithMessages: session {} not found; returning null\", id"));
        assertFalse(source.contains("세션을 찾을 수 없습니다: \" + sessionId"));
        assertFalse(source.contains("관리자를 찾을 수 없습니다: \" + username"));
        assertFalse(source.contains("ownerKey={} title='{}'"));
        assertFalse(source.contains("getSessionsForUser (guest): keys={}\""));
        assertFalse(source.contains("title='{}'"));
        assertFalse(source.contains("관리자가 세션을 시작했습니다. title='{}'"));
        assertFalse(source.contains("세션 {}: 첫 사용자 메시지 저장 완료"));

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[History] ownerKeyResolver failed errorHash={} errorLength={}"));
        assertTrue(source.contains("[History] saveReturning failed sessionHash={} errorHash={} errorLength={}"));
        assertTrue(source.contains("[History] rolling summary update failed sessionHash={} errorHash={} errorLength={}"));
        assertTrue(source.contains("[History] rolling summary load failed sessionHash={} errorHash={} errorLength={}"));
        assertTrue(source.contains("[History] rolling summary parse failed errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("hash12(String.valueOf(sessionId))"));
        assertTrue(source.contains("sessionHash"));
        assertTrue(source.contains("session_not_found sessionHash="));
        assertTrue(source.contains("admin_not_found adminHash="));
        assertTrue(source.contains("Unsupported role roleHash={} roleLength={}"));
        assertTrue(source.contains("ownerKeyHash"));
        assertTrue(source.contains("getSessionsForUser (guest): keyCount={} keyHashes={}"));
        assertTrue(source.contains("keys.stream().map(ChatHistoryServiceImpl::hash12).toList()"));
        assertTrue(source.contains("titleHash"));
        assertTrue(source.contains("titleLength"));
        assertTrue(source.contains("adminHash"));
    }

    @Test
    void failSoftFallbacksLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/ChatHistoryServiceImpl.java"));

        assertTrue(source.contains("traceSuppressed(\"history.guestOwnerHash\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.skipWeakAssistant.append\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.skipWeakAssistant.returningId\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.answerMeta.read\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.answerMeta.write\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.answerMeta.save\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.latestConversationMessageId\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.anchorTokenizer\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.readLong\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.readInt\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"history.readDouble\", ignore);"));
    }

    @Test
    void rollingSummaryNumericFallbacksUseStableInvalidNumberTraceType() throws Exception {
        String raw = "ownerToken=secret-not-a-number";
        Method readLong = ChatHistoryServiceImpl.class.getDeclaredMethod("readLong", Object.class);
        Method readInt = ChatHistoryServiceImpl.class.getDeclaredMethod("readInt", Object.class);
        Method readDouble = ChatHistoryServiceImpl.class.getDeclaredMethod("readDouble", Object.class, double.class);
        readLong.setAccessible(true);
        readInt.setAccessible(true);
        readDouble.setAccessible(true);

        TraceStore.clear();
        assertNull(readLong.invoke(null, raw));
        assertEquals("invalid_number", TraceStore.get("history.suppressed.history.readLong.errorType"));

        TraceStore.clear();
        assertEquals(0, readInt.invoke(null, raw));
        assertEquals("invalid_number", TraceStore.get("history.suppressed.history.readInt.errorType"));

        TraceStore.clear();
        assertEquals(0.75d, readDouble.invoke(null, raw, 0.75d));
        assertEquals("invalid_number", TraceStore.get("history.suppressed.history.readDouble.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
    }
}
