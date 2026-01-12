package com.example.lms.service.prompt;

import com.example.lms.service.prompt.model.PromptContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;



public class EvidencePromptBuilder {

    public String build(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("### INSTRUCTIONS\n");
        sb.append("- Synthesize answers from sources (higher authority first). Cite evidence. If evidence is insufficient, explain what is not confirmed; reply '정보 없음' only when there is truly no relevant evidence.\n\n");
        sb.append("- For unreleased/next-gen products, rumors/leaks in the evidence are acceptable ONLY if you clearly label them as (루머)/(유출)/(예상) and state that details may change before release.\n\n");

        sb.append("- 답변의 각 문장 끝에는 반드시 [1], [2]와 같은 형식으로 출처 번호를 붙이세요. " +
                  "인용 번호가 전혀 없으면 시스템이 답변을 거부하거나 경고를 남길 수 있습니다.\n");
        sb.append("- 위의 Evidence 목록에서 관련 있는 항목 번호를 사용해 [W1], [D2]처럼 근거와 답변을 연결하세요.\n\n");
        sb.append("- 검색 결과나 Evidence를 사용할 때는 가능한 한 문장 끝에 실제 URL을 함께 표기하세요. " +
                  "형식 예시: [[https://example.com/...]] 과 같이 대괄호 두 개로 URL을 감쌉니다.\n");
        sb.append("- URL 없이 단순 [1], [2] 번호만 사용하면 메모리 강화 단계에서 답변이 저장되지 않을 수 있습니다.\n");
        sb.append("- Evidence 목록에 이미 포함된 URL을 그대로 복사해 사용하는 것을 우선으로 합니다.\n\n");

        sb.append("### Evidence (pinned)\n");
        int docId = 1;
        for (String s : nonNull(ctx.webSnippets())) {
            appendEvidenceLine(sb, "W", docId++, s);
        }
        for (String s : nonNull(ctx.vectorSnippets())) {
            appendEvidenceLine(sb, "V", docId++, s);
        }
        sb.append("\n");

        Set<String> tokens = new LinkedHashSet<>();
        for (String s : nonNull(ctx.webSnippets())) tokens.addAll(extractTokens(s));
        for (String s : nonNull(ctx.vectorSnippets())) tokens.addAll(extractTokens(s));

        sb.append("### Task\n");
        sb.append("Use the evidence above. Retain key proper nouns from the evidence. Provide a concise, direct answer first, then a short rationale.\n");
        sb.append("Never claim '정보 없음' if relevant evidence exists.\n\n");

        sb.append("### Must-include entities (at least 2 if present):\n");
        for (String t : tokens) {
            sb.append("- ").append(t).append("\n");
        }
        return sb.toString();
    }

    private static List<String> nonNull(List<String> in) {
        return in == null ? List.of() : in;
    }

    /**
     * Evidence 라인의 텍스트에서 URL을 탐지해 "(출처: URL)" 형태로 노출해 주는 헬퍼.
     * 이렇게 해 두면 LLM이 답변을 만들 때 URL을 그대로 복사해 사용하기가 쉬워진다.
     */
    private static void appendEvidenceLine(StringBuilder sb, String typePrefix, int docId, String raw) {
        if (raw == null) {
            raw = "";
        }
        String url = extractFirstUrl(raw);
        String text = raw;
        if (url != null) {
            // 본문에서 URL을 한 번 제거해 중복 표기를 줄인다.
            text = raw.replace(url, "").trim();
            if (text.isEmpty()) {
                text = "(출처: " + url + ")";
                sb.append("- [").append(typePrefix).append(docId).append("] ")
                  .append(text)
                  .append("\n");
                return;
            }
            sb.append("- [").append(typePrefix).append(docId).append("] ")
              .append(text)
              .append(" (출처: ").append(url).append(")")
              .append("\n");
        } else {
            sb.append("- [").append(typePrefix).append(docId).append("] ")
              .append(raw)
              .append("\n");
        }
    }

    private static String extractFirstUrl(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static final Pattern TOKEN_SPLIT =
            Pattern.compile("[\n\r\t ,;:/()\\[\\]{}<>|]+");

    private static final Pattern URL_PATTERN =
            Pattern.compile("(https?://\\S+)");

    private static List<String> extractTokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : TOKEN_SPLIT.split(s)) {
            if (t.length() >= 2 && !isNoise(t)) out.add(t);
        }
        return out;
    }

    private static boolean isNoise(String t) {
        String x = t.toLowerCase();
        return x.equals("the") || x.equals("and") || x.equals("or") || x.equals("with") || x.equals("of");
    }
}