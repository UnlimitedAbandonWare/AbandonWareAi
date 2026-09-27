package com.example.lms.service;

import java.util.regex.Pattern;

/**
 * 문서·첨부 범위 한정 질의 감지 (요청 로컬, 외부 호출 없음).
 *
 * <p>사용자가 "첨부 문서만", "제공된 자료로만", "based only on the attached file"처럼
 * 답변 근거 범위를 문서/첨부로 제한했는지 판정한다. {@code evidence_needed} 출력 지시
 * ({@link EvidenceNeededDirectivePolicy})와는 별개의 범위 한정 의도만 본다.</p>
 *
 * <p>범위 한정 질의에서 검색 결과가 비었을 때 일반 지식 답변으로 대체하면
 * 사용자가 금지한 출처를 사용한 셈이 되므로, 호출 측은 자료 부족/실행 실패
 * 안내를 유지해야 한다.</p>
 */
final class DocumentScopeQuestionPolicy {

    private DocumentScopeQuestionPolicy() {
    }

    // "문서만/첨부만/자료만으로 근거" 류 — 범위 한정이 명시된 표현
    private static final Pattern RESTRICTIVE_KO = Pattern.compile(
            "(첨부(한|된)?\\s*(문서|파일|자료|내용)?\\s*(만|으로만|에서만|에만|기반으로|근거로)"
                    + "|(문서|파일|자료|논문|리포트)\\s*(만|에서만|안에서만)\\s*(근거|기반|참고|답|보고|요약|설명)"
                    + "|제공(된|한)?\\s*(문서|자료|파일|정보|텍스트|내용)\\s*(만|으로만|에서만|내에서만)"
                    + "|(이|그|해당)\\s*(문서|파일|자료|논문)\\s*(만|에서만|기준으로|안에서|에\\s*따르면|에\\s*의하면)"
                    + "|(문서|파일|자료)\\s*내용(만|으로만)"
                    + "|근거(로만|만으로)\\s*답"
                    + "|주어진\\s*(문서|자료|텍스트|글)\\s*(만|에서만|으로만))");

    private static final Pattern RESTRICTIVE_EN = Pattern.compile(
            "\\b(based\\s+only\\s+on\\s+(the\\s+)?(attached|provided|uploaded|given|this)?"
                    + "\\s*(document|file|text|attachment|material|source)s?"
                    + "|only\\s+(from|using|with|in)\\s+the\\s+(attached|provided|uploaded|given)"
                    + "\\s+(document|file|text|material|source)s?"
                    + "|(attached|provided|uploaded)\\s+(document|file|text|material)s?\\s+only"
                    + "|answer\\s+(only\\s+)?(from|using)\\s+the\\s+(document|file|attachment|provided\\s+text)"
                    + "|according\\s+to\\s+the\\s+(attached|provided|uploaded)\\s+(document|file|text)"
                    + "|within\\s+the\\s+(provided|attached|given)\\s+(document|text|material)s?\\b)",
            Pattern.CASE_INSENSITIVE);

    // 첨부가 실제 달렸을 때 문서를 가리키는 완화 참조 표현
    private static final Pattern ATTACHMENT_REFERENCE = Pattern.compile(
            "(첨부|올린|업로드|이\\s*문서|이\\s*파일|그\\s*문서|그\\s*파일|해당\\s*문서"
                    + "|(문서|파일|자료)\\s*(를|을|의)?\\s*(요약|정리|설명|분석|내용|핵심|의미)"
                    + "|\\battached\\b|\\battachment\\b|\\bthe\\s+document\\b|\\bthis\\s+document\\b"
                    + "|\\bthe\\s+file\\b|\\bthis\\s+file\\b|\\buploaded\\b"
                    + "|\\bprovided\\s+(document|file|text)\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 문서/첨부 범위 한정 질의인지 반환한다.
     *
     * @param attachmentCount 실제 첨부 개수. 0이면 완화 참조 표현만으로는 한정하지 않는다
     *                          (명시적 범위 한정 표현은 첨부가 없어도 적용).
     */
    static boolean isDocumentScopeBound(String userQuery, int attachmentCount) {
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }
        String q = userQuery.strip();
        if (RESTRICTIVE_KO.matcher(q).find() || RESTRICTIVE_EN.matcher(q).find()) {
            return true;
        }
        return attachmentCount > 0 && ATTACHMENT_REFERENCE.matcher(q).find();
    }
}
