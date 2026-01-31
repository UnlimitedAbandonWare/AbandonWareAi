
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
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * 여러 소스(대화 기록, 지식 베이스)에서 '증거'로 사용할 텍스트 스니펫을 가져와 정규화하는 어댑터입니다.
 * <p>
 * 1. 리플렉션을 사용하여 다양한 서비스의 메서드 시그니처 차이를 유연하게 처리하며 데이터를 <b>가져옵니다</b>.
 * 2. 가져온 데이터에서 빈 줄, 메타데이터, 중복 등을 <b>정리(정규화)</b>하여 순수한 텍스트 증거 목록을 생성합니다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class MemoryAsEvidenceAdapter {
    private static final Logger log = LoggerFactory.getLogger(MemoryAsEvidenceAdapter.class);

    private final ChatHistoryService history;

    // KnowledgeBaseService는 선택적으로 주입됩니다.
    @Autowired(required = false)
    private KnowledgeBaseService kb;

    /**
     * 특정 세션 ID에 해당하는 최근 대화 기록에서 검증된 답변을 증거로 추출하고 정규화합니다.
     *
     * @param sessionId 대상 세션 ID
     * @param lastN     가져올 최대 답변 개수
     * @return 정규화된 증거 문자열 목록
     */
    public List<String> fromSession(Long sessionId, int lastN) {
        if (sessionId == null) return Collections.emptyList();

        List<String> rawSnippets = new ArrayList<>();

        // 리플렉션을 사용하여 ChatHistoryService의 메서드를 유연하게 호출
        // 1순위: getRecentVerifiedAssistantMessages(Long, int)
        try {
            Method m = history.getClass().getMethod("getRecentVerifiedAssistantMessages", Long.class, int.class);
            Object list = m.invoke(history, sessionId, lastN);
            collectAssistantContents(list, rawSnippets, lastN);
            return normalize(rawSnippets);
        } catch (NoSuchMethodException ignore) {
            // 2순위 메서드로 넘어감
        } catch (Throwable t) {
            log.warn("getRecentVerifiedAssistantMessages 호출 실패, 폴백 시도: {}", t.getMessage());
        }

        // 2순위: getSessionWithMessages(Long)
        try {
            Method m = history.getClass().getMethod("getSessionWithMessages", Long.class);
            Object session = m.invoke(history, sessionId);
            if (session != null) {
                Method getMessages = session.getClass().getMethod("getMessages");
                Object messages = getMessages.invoke(session);
                collectAssistantContents(messages, rawSnippets, lastN);
            }
        } catch (Throwable t) {
            log.error("getSessionWithMessages 폴백 호출 실패: {}", t.getMessage());
        }

        return normalize(rawSnippets);
    }

    /**
     * Knowledge Base에서 주제와 관련된 스니펫을 증거로 추출하고 정규화합니다.
     *
     * @param domain  지식 도메인
     * @param subject 관련 주제
     * @param max     가져올 최대 스니펫 개수
     * @return 정규화된 증거 문자열 목록
     */
    public List<String> fromKb(String domain, String subject, int max) {
        if (kb == null) return Collections.emptyList();

        List<String> rawSnippets = new ArrayList<>();
        try {
            Method m = kb.getClass().getMethod("getSnippets", String.class, String.class, int.class);
            Object list = m.invoke(kb, domain, subject, max);
            addStringList(list, rawSnippets, max);
        } catch (NoSuchMethodException ignore) {
            log.debug("KnowledgeBaseService에 getSnippets(String,String,int) 메서드가 없습니다.");
        } catch (Throwable t) {
            log.error("KB 스니펫 가져오기 실패: {}", t.getMessage());
        }

        return normalize(rawSnippets);
    }

    // --- Private Helper Methods ---

    /**
     * (Version 1의 기능) 문자열 목록을 받아 빈 줄, 주석, 중복을 제거하여 정규화합니다.
     */
    private List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();

        return raw.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#") && s.length() > 1)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 리플렉션으로 얻은 객체 목록에서 'assistant' 역할의 메시지 내용만 추출합니다.
     */
    @SuppressWarnings("unchecked")
    private void collectAssistantContents(Object listLike, List<String> out, int limit) {
        List<Object> list = toList(listLike);
        if (list.isEmpty()) return;

        // 뒤에서부터 최근 N개만 보도록 순서 뒤집기
        Collections.reverse(list);

        for (Object msg : list) {
            if (out.size() >= limit) break;
            String role = invokeString(msg, "getRole");
            if (role != null && "assistant".equalsIgnoreCase(role)) {
                String content = invokeString(msg, "getContent");
                if (content != null && !content.isBlank()) {
                    out.add(content);
                }
            }
        }
        // 원래 순서로 다시 뒤집어 시간 순서 유지
        Collections.reverse(out);
    }

    private void addStringList(Object listLike, List<String> out, int limit) {
        List<Object> list = toList(listLike);
        for (Object o : list) {
            if (o == null) continue;
            String s = String.valueOf(o);
            if (!s.isBlank()) out.add(s);
            if (out.size() >= limit) break;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> toList(Object maybeList) {
        if (maybeList instanceof List) {
            return (List<Object>) maybeList;
        }
        return Collections.emptyList();
    }



    private String invokeString(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return (v == null) ? null : v.toString();
        } catch (Exception ignore) {
            return null;
        }
    }
}