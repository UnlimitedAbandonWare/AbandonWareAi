package com.example.lms.infra.resilience;

import com.example.lms.service.guard.InfoFailurePatterns;

import java.util.Locale;

/**
 * UAW 미구현 축: 정상 응답처럼 보이는 "사과/정보없음/회피"를 silent failure로 감지.
 * NightmareBreaker에 기록하여 서킷 오픈 판단에 활용.
 *
 * <p>주의: 이 검사는 "문장형 답변"에 대한 휴리스틱이다.
 * JSON/코드블록 같은 구조화 출력은 여기서 failure로 단정하지 않고, 다운스트림 파서/검증기가
 * 의미/형태를 판단하도록 넘긴다.</p>
 */
public final class FriendShieldPatternDetector {
    private FriendShieldPatternDetector() {}

    public static boolean looksLikeSilentFailure(String text) {
        if (text == null || text.isBlank()) return true;

        String trimmed = text.trim();

		// UAW: empty structured payload should be treated as silent failure
		if (looksLikeEmptyStructuredPayload(trimmed)) {
			return true;
		}

        // 구조화 출력(JSON/코드블록/YAML-ish)은 silent failure로 단정하지 않음
        if (looksLikeStructuredPayload(trimmed)) return false;

        if (InfoFailurePatterns.looksLikeFailure(trimmed)) return true;

        String lower = trimmed.toLowerCase(Locale.ROOT);

        // 짧은 회피성 응답만 포착 (길이가 길면 실제 내용이 섞일 가능성이 높음)
        if (trimmed.length() <= 200) {
            if (lower.contains("죄송") || lower.contains("유감") || lower.contains("sorry")) return true;
            if (lower.contains("답변할 수") || lower.contains("알 수 없") || lower.contains("모르겠")) return true;
            if (lower.contains("cannot") || lower.contains("can't") || lower.contains("unable")) return true;
        }

	        return false;
    }

	/**
	 * UAW: Empty structured payload detection.
	 * e.g., ```json ... ``` with empty content, {}, []
	 */
	private static boolean looksLikeEmptyStructuredPayload(String trimmed) {
		if (trimmed == null || trimmed.isBlank()) return true;
		String t = trimmed.trim();
		if (t.isEmpty()) return true;

		// If wrapped by a code fence, evaluate the inner content.
		if (t.startsWith("```") && t.endsWith("```")) {
			String inner = t
					.replaceFirst("^```(?:json)?\\s*", "")
					.replaceFirst("\\s*```\\s*$", "")
					.trim();
			if (inner.isEmpty()) return true;
			t = inner;
		}

		String compact = t.replaceAll("\\s+", "");
		return "{}".equals(compact) || "[]".equals(compact);
	}

    private static boolean looksLikeStructuredPayload(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.isEmpty()) return false;

        // Fenced code block
        if (s.startsWith("```") && s.endsWith("```")) return true;

        // JSON object/array
        if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) return true;

        // Minimal YAML-ish (multi-line key: value)
        int nl = s.indexOf('\n');
        if (nl > 0) {
            String first = s.substring(0, nl);
            if (first.contains(":")) return true;
        }
        return false;
    }
}
