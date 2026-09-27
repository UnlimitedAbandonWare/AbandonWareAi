package com.example.lms.service.answer;

import java.util.regex.Pattern;

/**
 * 정확-출력 계약 감지: 사용자가 숫자 하나·한 단어·JSON·정확 토큰 등 원자적 출력을
 * 요구했으면 선택적 답변 확장(보조 LLM 재작성)을 억제한다.
 *
 * <p>확장은 텍스트를 추가하는 경로라 원자적 계약과 구조적으로 충돌한다. 계약이
 * 선언된 요청에서는 초안이 이미 조건을 만족하는지와 무관하게 확장 호출 자체를
 * 생략하고, 최종 형태 정리는 postprocessor의 exact-output shaping에 맡긴다.</p>
 */
public final class ExactOutputContractGuard {

    private ExactOutputContractGuard() {
    }

    // "숫자만/숫자로만/숫자 하나로/답을 숫자로/only the number/number only/digits only"
    private static final Pattern NUMERIC_ONLY = Pattern.compile(
            "(숫자\\s*(만|로만|으로만|하나|한\\s*개)"
                    + "|(만|하나|한\\s*개)\\s*숫자"
                    + "|답(을|은)?\\s*숫자(로|만)"
                    + "|\\b(only\\s+the\\s+number|number\\s+only|numeric\\s+only|single\\s+number"
                    + "|digits?\\s+only|one\\s+number|just\\s+the\\s+number)\\b)",
            Pattern.CASE_INSENSITIVE);

    // 한 단어/예·아니오/정답만/결과만 계열
    private static final Pattern ATOMIC_TOKEN = Pattern.compile(
            "(한\\s*단어(로|만)\\s*만?|단어\\s*하나(로|만)\\s*만?"
                    + "|예\\s*(또는|/|아니면)\\s*아니오|예/아니오|아니오\\s*(또는|/)\\s*예"
                    + "|\\b(one|single)\\s+word\\b|\\byes\\s+or\\s+no\\b|\\byes/no\\b"
                    + "|정답만|결과만\\s*(출력|답|말)|답만\\s*(출력|말|적어))",
            Pattern.CASE_INSENSITIVE);

    // "reply with X only", "정확히 X만 출력" 류
    private static final Pattern EXACT_REPLY = Pattern.compile(
            "(\\b(reply|respond|answer)\\s+with\\b[^\\n]{0,60}\\bonly\\b"
                    + "|\\boutput\\s+only\\b|\\bprint\\s+only\\b"
                    + "|정확히\\s*[^\\n]{0,30}(만|으로만)\\s*(출력|답|표시|적어))",
            Pattern.CASE_INSENSITIVE);

    // JSON/코드 전용 출력
    private static final Pattern STRUCTURED_ONLY = Pattern.compile(
            "(json\\s*(만|으로만|형식으로만|형태로만)|json\\s*형식(만|으로)"
                    + "|\\bjson\\s*only\\b|\\bonly\\s+json\\b|\\bin\\s+json\\b|\\bas\\s+json\\b"
                    + "|코드만\\s*(출력|작성|적어)|\\bcode\\s+only\\b|\\bonly\\s+(the\\s+)?code\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 원자적/정확 출력 계약이 선언된 요청이면 선택적 확장을 건너뛴다.
     * draft를 검사하지 않는 이유: 계약이 있는 요청에서 확장은 결과를 더 길게
     * 만들 뿐 계약 준수에 기여하지 않으므로, 초안 상태와 무관하게 호출을 생략한다.
     */
    public static boolean suppressesExpansion(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }
        String q = userQuery.strip();
        return NUMERIC_ONLY.matcher(q).find()
                || ATOMIC_TOKEN.matcher(q).find()
                || EXACT_REPLY.matcher(q).find()
                || STRUCTURED_ONLY.matcher(q).find();
    }
}
