// src/main/java/com/example/lms/service/rag/guard/MemoryAsEvidenceAdapter.java
package com.example.lms.service.rag.guard;

import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 최근 세션의 "검증 통과" 답변과 KB 레코드를 간단 스니펫으로 변환.
 * 프로젝트별 Service 시그니처 차이를 흡수하기 위해 리플렉션 폴백을 사용한다.
 */
@Component
@RequiredArgsConstructor
public class MemoryAsEvidenceAdapter {

    private final ChatHistoryService history;

    @Autowired(required = false)
    private KnowledgeBaseService kb; // 없으면 KB 스니펫은 비움

    /** 최근 검증(또는 최근) assistant 답변을 최대 lastN개 추출 */
    public List<String> fromSession(Long sessionId, int lastN) {
        List<String> out = new ArrayList<>();
        if (sessionId == null) return out;

        // 1) (있으면) getRecentVerifiedAssistantMessages(Long, int) 우선
        try {
            Method m = history.getClass().getMethod("getRecentVerifiedAssistantMessages", Long.class, int.class);
            Object list = m.invoke(history, sessionId, lastN);
            collectAssistantContents(list, out, lastN);
            return out;
        } catch (NoSuchMethodException ignore) {
            // fallthrough
        } catch (Throwable t) {
            // fallthrough
        }

        // 2) 폴백: getSessionWithMessages(Long) → messages[].role == "assistant"
        try {
            Method m = history.getClass().getMethod("getSessionWithMessages", Long.class);
            Object session = m.invoke(history, sessionId);
            if (session == null) return out;

            Method getMessages = session.getClass().getMethod("getMessages");
            Object messages = getMessages.invoke(session);

            collectAssistantContents(messages, out, lastN);
        } catch (Throwable ignore) {
            // 안전 무시
        }
        return out;
    }

    /** 도메인/주제 기반 KB 스니펫(존재할 때만) */
    public List<String> fromKb(String domain, String subject, int max) {
        List<String> out = new ArrayList<>();
        if (kb == null) return out;

        // 1) (있으면) getSnippets(String,String,int)
        try {
            Method m = kb.getClass().getMethod("getSnippets", String.class, String.class, int.class);
            Object list = m.invoke(kb, domain, subject, max);
            addStringList(list, out, max);
            return out;
        } catch (NoSuchMethodException ignore) {
            // fallthrough
        } catch (Throwable t) {
            // fallthrough
        }

        // 2) 다른 API가 있으면 필요 시 추가(예: getFacts, getSummary 등). 기본은 빈 리스트.
        return out;
    }

    // ───────────────────── helpers ─────────────────────

    @SuppressWarnings("unchecked")
    private static void collectAssistantContents(Object listLike, List<String> out, int limit) {
        List<Object> list = toList(listLike);
        if (list.isEmpty()) return;

        // 뒤에서부터 최근 N개
        int start = Math.max(0, list.size() - Math.max(1, limit));
        for (int i = start; i < list.size(); i++) {
            Object msg = list.get(i);
            String role = invokeString(msg, "getRole");
            if (role != null && role.equalsIgnoreCase("assistant")) {
                String content = invokeString(msg, "getContent");
                if (content != null && !content.isBlank()) out.add(content);
            }
        }
    }

    private static void addStringList(Object listLike, List<String> out, int limit) {
        List<Object> list = toList(listLike);
        for (Object o : list) {
            if (o == null) continue;
            String s = String.valueOf(o);
            if (!s.isBlank()) out.add(s);
            if (out.size() >= limit) break;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toList(Object maybeList) {
        if (maybeList instanceof List<?> l) return (List<Object>) l;
        return Collections.emptyList();
    }

    private static String invokeString(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            return (v == null) ? null : v.toString();
        } catch (Throwable ignore) {
            return null;
        }
    }
}
