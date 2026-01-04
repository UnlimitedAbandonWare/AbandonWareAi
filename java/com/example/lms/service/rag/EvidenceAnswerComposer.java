package com.example.lms.service.rag;

import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.util.FutureTechDetector;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Composes a conservative, evidence-based answer when the main LLM path is unavailable
 * (or intentionally bypassed), using only already-retrieved evidence.
 *
 * <p>This component MUST be deterministic (no LLM calls). It is used specifically
 * in fallback paths where LLM timeouts/unavailability are expected.
 */
@Component
public class EvidenceAnswerComposer {

    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{Nd}]{2,}");

    // A minimal, pragmatic stopword list for KR/EN. Keep it small to avoid over-filtering.
    private static final Set<String> STOP = Set.of(
            "그", "이", "저", "것", "수", "등", "및", "대한", "관련", "내용", "정보", "설명",
            "알려", "알려줘", "뭐", "무엇", "어떤", "왜", "어떻게", "언제", "어디", "누구",
            "좀", "해주세요", "해줘", "하기",
            "the", "a", "an", "and", "or", "to", "of", "in", "for", "on", "with", "is",
            "are", "was", "were"
    );

    /**
     * Build an answer that summarises the retrieved evidence.
     *
     * @param userQuestion  original user query
     * @param evidence      list of evidence documents
     * @param lowRiskDomain whether the domain is considered low-risk
     * @return formatted answer string
     */
    public String compose(String userQuestion,
                          List<EvidenceAwareGuard.EvidenceDoc> evidence,
                          boolean lowRiskDomain) {

        if (evidence == null || evidence.isEmpty()) {
            return "검색 결과가 충분하지 않아 답변을 구성하기 어렵습니다.";
        }

        StringBuilder sb = new StringBuilder();

        boolean futureTech = FutureTechDetector.isFutureTechQuery(userQuestion);

        // 1) Header: explain the situation and risk level.
        if (futureTech) {
            sb.append("※ 아래 내용은 공식 발표 전이며, 검색 결과에서 수집된 루머/유출/예상 정보 기반입니다. 실제 출시 시 변경될 수 있습니다.\n\n");
        } else if (lowRiskDomain) {
            sb.append("공식 출처는 아니지만, 검색 결과(커뮤니티/위키 포함)를 바탕으로 핵심을 정리했습니다.\n\n");
        } else {
            sb.append("검색된 자료를 바탕으로 정리했으나, 공식 문서는 아닐 수 있습니다.\n\n");
        }

        // 1.5) Provide a short explanation BEFORE listing raw evidence.
        // This remains deterministic (no LLM calls).
        String explanation = buildExtractiveExplanation(userQuestion, evidence);
        if (!explanation.isBlank()) {
            sb.append("### 핵심 설명\n");
            sb.append(explanation).append("\n\n");
        }

        // 2) Evidence bullets (top N).
        sb.append("### 근거(검색 결과)\n");
        int limit = Math.min(5, evidence.size());
        for (int i = 0; i < limit; i++) {
            EvidenceAwareGuard.EvidenceDoc doc = evidence.get(i);
            if (doc == null) {
                continue;
            }
            String title = safe(doc.title(), "제목 없음");
            String snippet = sanitizeSnippet(safe(doc.snippet(), ""));
            if (snippet.length() > 180) {
                snippet = snippet.substring(0, 177) + "...";
            }
            String id = safe(doc.id(), "");
            sb.append("- **").append(title).append("**: ").append(snippet);
            if (!id.isBlank()) {
                sb.append(" (출처: ").append(id).append(")");
            }
            sb.append("\n");
        }

        // 3) Disclaimer.
        if (futureTech) {
            sb.append("\n> ⚠️ 위 내용은 공식 발표 전이며, 유출/루머에 기반한 정보일 수 있습니다. 확정된 사실처럼 단정하지 말고, 공식 발표/리뷰가 나오면 다시 확인해 주세요.\n");
        } else if (lowRiskDomain) {
            sb.append("\n> ⚠️ 위 내용은 비공식 자료(위키/커뮤니티) 기반일 수 있습니다. 공식 문서/공식 발표/1차 출처를 함께 확인해 주세요.\n");
        } else {
            sb.append("\n> ⚠️ 이 정보는 비공식 자료에 기반할 수 있으므로, 공식 문서/1차 출처로 교차 확인해 주세요.\n");
        }

        return sb.toString();
    }

    /**
     * Extractive, deterministic explanation.
     *
     * <p>We do NOT invent facts here. We select a few high-signal sentences from
     * evidence snippets, biased toward keywords in the user's question.
     */
    private static String buildExtractiveExplanation(String userQuestion,
                                                     List<EvidenceAwareGuard.EvidenceDoc> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "";
        }

        List<String> keywords = extractKeywords(userQuestion);
        Set<String> seen = new HashSet<>();
        List<String> bullets = new ArrayList<>();

        int scanDocs = Math.min(5, evidence.size());
        for (int i = 0; i < scanDocs && bullets.size() < 4; i++) {
            EvidenceAwareGuard.EvidenceDoc doc = evidence.get(i);
            if (doc == null) {
                continue;
            }

            String snippet = sanitizeSnippet(doc.snippet());
            if (snippet.isBlank()) {
                continue;
            }

            String best = null;
            int bestScore = -1;
            for (String sent : splitSentences(snippet)) {
                String s = sent.trim();
                if (s.length() < 18) {
                    continue;
                }
                int score = keywordScore(s, keywords);
                if (score > bestScore) {
                    bestScore = score;
                    best = s;
                }
            }

            if (best == null) {
                for (String sent : splitSentences(snippet)) {
                    String s = sent.trim();
                    if (s.length() >= 18) {
                        best = s;
                        break;
                    }
                }
            }
            if (best == null) {
                continue;
            }

            String norm = normalizeForDedupe(best);
            if (!seen.add(norm)) {
                continue;
            }

            best = truncate(best, 220);
            String ref = safe(doc.id(), "");
            if (!ref.isBlank()) {
                best = best + " (출처: " + ref + ")";
            }
            bullets.add("- " + best);
        }

        if (bullets.isEmpty()) {
            return "";
        }
        return String.join("\n", bullets);
    }

    private static List<String> extractKeywords(String q) {
        List<String> out = new ArrayList<>();
        if (q == null) {
            return out;
        }

        String s = q.toLowerCase();
        java.util.regex.Matcher m = TOKEN.matcher(s);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String tok = m.group();
            if (tok == null) {
                continue;
            }
            tok = tok.trim();
            if (tok.isEmpty()) {
                continue;
            }
            if (STOP.contains(tok)) {
                continue;
            }
            if (seen.add(tok)) {
                out.add(tok);
                if (out.size() >= 10) {
                    break;
                }
            }
        }
        return out;
    }

    private static int keywordScore(String sentence, List<String> keywords) {
        if (sentence == null || sentence.isBlank() || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        String s = sentence.toLowerCase();
        int score = 0;
        for (String k : keywords) {
            if (k == null || k.isBlank()) {
                continue;
            }
            if (s.contains(k)) {
                score += 1;
            }
        }
        return score;
    }

    private static String sanitizeSnippet(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        s = HTML_TAGS.matcher(s).replaceAll(" ");
        s = s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }

        // Simple splitter for KR/EN snippets.
        String[] parts = text.split("(?<=[.!?])\\s+|(?<=다\\.)\\s+|(?<=다\\?)\\s+|(?<=다!)\\s+");
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        if (out.isEmpty() && !text.trim().isEmpty()) {
            out.add(text.trim());
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        if (max < 10) {
            return t.substring(0, max);
        }
        return t.substring(0, max - 3) + "...";
    }

    private static String normalizeForDedupe(String s) {
        if (s == null) {
            return "";
        }
        String t = s.toLowerCase().trim();
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    private static String safe(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
