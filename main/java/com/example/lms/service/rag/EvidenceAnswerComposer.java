package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.trace.SafeRedactor;
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

    private static final String NO_RELEVANT_EVIDENCE_ANSWER =
            "검색 결과가 질문과 충분히 맞지 않아 답변을 구성하기 어렵습니다.";
    private static final String OFFICIAL_NO_RELEVANT_EVIDENCE_ANSWER =
            "evidence_needed: 공식/changelog 근거를 현재 검색 결과에서 확인하지 못했습니다. "
                    + "공식 출처가 확보되기 전까지 추측 답변은 제한합니다.";
    private static final String CONDITIONAL_HOLD_CONSTRAINT =
            "If evidence is insufficient, explicitly answer HOLD.";
    private static final String HOLD_TOKEN =
            "(?<![A-Za-z0-9_])HOLD(?![A-Za-z0-9_])";
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{Nd}]{2,}");
    private static final Pattern CONDITIONAL_HOLD = Pattern.compile(
            "(?:\\bif\\b|\\bwhen\\b|\\bunless\\b|insufficient|not\\s+enough|부족|충분하지)"
                    + ".{0,120}" + HOLD_TOKEN,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final Pattern NEGATED_HOLD = Pattern.compile(
            "(?:do\\s+not|don't|dont|never|without|not\\s+(?:answer|return|output|respond)|"
                    + "답하지|출력하지|사용하지|제외).{0,80}" + HOLD_TOKEN,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final Pattern POST_HOLD_NEGATION = Pattern.compile(
            HOLD_TOKEN + ".{0,48}(?:답(?:변)?하지|출력하지|사용하지|제외)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final Pattern QUOTED_HOLD = Pattern.compile(
            "[\\\"'`“”‘’]\\s*HOLD\\s*[\\\"'`“”‘’]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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

        int evidenceCount = evidence == null ? 0 : evidence.size();
        traceInput(userQuestion, evidenceCount, lowRiskDomain);
        if (evidence == null || evidence.isEmpty()) {
            traceResult(0, false, evidenceCount, false);
            return noUsableEvidenceAnswer(userQuestion);
        }

        boolean futureTech = FutureTechDetector.isFutureTechQuery(userQuestion);
        TraceStore.put("evidenceAnswerComposer.futureTech", futureTech);

        EvidenceIntent evidenceIntent = detectEvidenceIntent(userQuestion);
        boolean conditionalHoldRequested = requestedConditionalHold(userQuestion);
        List<EvidenceAwareGuard.EvidenceDoc> usableEvidence = usableEvidenceDocs(evidence);
        usableEvidence = prioritizeEvidenceDocs(usableEvidence, evidenceIntent);
        int priorityEvidenceCount = countPriorityEvidence(usableEvidence, evidenceIntent);
        TraceStore.put("evidenceAnswerComposer.officialOrChangelogIntent", evidenceIntent.officialOrChangelog());
        TraceStore.put("evidenceAnswerComposer.conditionalHoldRequested", conditionalHoldRequested);
        TraceStore.put("evidenceAnswerComposer.priorityEvidenceCount", priorityEvidenceCount);
        TraceStore.put("evidenceAnswerComposer.usableEvidenceCount", usableEvidence.size());
        TraceStore.put("evidenceAnswerComposer.filteredMetadataEvidenceCount",
                Math.max(0, evidenceCount - usableEvidence.size()));
        if (usableEvidence.isEmpty()) {
            traceResult(0, false, evidenceCount, false);
            return noUsableEvidenceAnswer(userQuestion);
        }

        StringBuilder sb = new StringBuilder();

        // 1) Header: explain the situation and risk level.
        if (futureTech) {
            sb.append("※ 아래 내용은 공식 발표 전이며, 검색 결과에서 수집된 루머/유출/예상 정보 기반입니다. 실제 출시 시 변경될 수 있습니다.\n\n");
        } else if (evidenceIntent.officialOrChangelog() && priorityEvidenceCount > 0) {
            sb.append("공식/changelog 성격의 근거를 우선해서 검색 결과를 추출형으로 정리했습니다. LLM 경로가 꺼져 있어 원문 근거 중심으로 확인해 주세요.\n\n");
        } else if (evidenceIntent.officialOrChangelog()) {
            sb.append("공식/changelog 근거를 요청했지만 현재 검색 결과에서는 뚜렷한 공식/릴리스 노트 근거가 부족합니다. 확인된 검색 결과만 제한적으로 정리합니다.\n\n");
        } else if (lowRiskDomain) {
            sb.append("공식 출처는 아니지만, 검색 결과(커뮤니티/위키 포함)를 바탕으로 핵심을 정리했습니다.\n\n");
        } else {
            sb.append("검색된 자료를 바탕으로 정리했으나, 공식 문서는 아닐 수 있습니다.\n\n");
        }

        if (evidenceIntent.officialOrChangelog() && conditionalHoldRequested) {
            sb.append("> 요청한 조건부 판정을 보존합니다: 근거가 충분하지 않다면 HOLD.\n\n");
        }

        // 1.5) Provide a short explanation BEFORE listing raw evidence.
        // This remains deterministic (no LLM calls).
        String explanation = buildExtractiveExplanation(userQuestion, usableEvidence);
        boolean explanationIncluded = !explanation.isBlank();
        if (explanationIncluded) {
            sb.append("### 핵심 설명\n");
            sb.append(explanation).append("\n\n");
        }

        // 2) Evidence bullets (top N).
        sb.append("### 근거(검색 결과)\n");
        int limit = Math.min(5, usableEvidence.size());
        int usedEvidenceCount = 0;
        for (int i = 0; i < limit; i++) {
            EvidenceAwareGuard.EvidenceDoc doc = usableEvidence.get(i);
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
            usedEvidenceCount++;
        }

        // 3) Disclaimer.
        if (futureTech) {
            sb.append("\n> ⚠️ 위 내용은 공식 발표 전이며, 유출/루머에 기반한 정보일 수 있습니다. 확정된 사실처럼 단정하지 말고, 공식 발표/리뷰가 나오면 다시 확인해 주세요.\n");
        } else if (lowRiskDomain) {
            sb.append("\n> ⚠️ 위 내용은 비공식 자료(위키/커뮤니티) 기반일 수 있습니다. 공식 문서/공식 발표/1차 출처를 함께 확인해 주세요.\n");
        } else {
            sb.append("\n> ⚠️ 이 정보는 비공식 자료에 기반할 수 있으므로, 공식 문서/1차 출처로 교차 확인해 주세요.\n");
        }

        traceResult(usedEvidenceCount, explanationIncluded, evidenceCount, true);
        return sb.toString();
    }

    private static List<EvidenceAwareGuard.EvidenceDoc> usableEvidenceDocs(
            List<EvidenceAwareGuard.EvidenceDoc> evidence) {
        List<EvidenceAwareGuard.EvidenceDoc> usable = new ArrayList<>();
        if (evidence == null || evidence.isEmpty()) {
            return usable;
        }
        for (EvidenceAwareGuard.EvidenceDoc doc : evidence) {
            if (doc == null) {
                continue;
            }
            String snippet = sanitizeSnippet(doc.snippet());
            if (snippet.isBlank()) {
                continue;
            }
            if (metadataOnlySnippet(doc, snippet)) {
                continue;
            }
            usable.add(doc);
        }
        return usable;
    }

    private static List<EvidenceAwareGuard.EvidenceDoc> prioritizeEvidenceDocs(
            List<EvidenceAwareGuard.EvidenceDoc> evidence,
            EvidenceIntent intent) {
        if (evidence == null || evidence.size() < 2 || intent == null || !intent.officialOrChangelog()) {
            return evidence;
        }
        evidence.sort((left, right) -> Integer.compare(
                evidenceIntentScore(right, intent),
                evidenceIntentScore(left, intent)));
        return evidence;
    }

    private static int countPriorityEvidence(List<EvidenceAwareGuard.EvidenceDoc> evidence,
                                             EvidenceIntent intent) {
        if (evidence == null || evidence.isEmpty() || intent == null || !intent.officialOrChangelog()) {
            return 0;
        }
        int count = 0;
        for (EvidenceAwareGuard.EvidenceDoc doc : evidence) {
            if (evidenceIntentScore(doc, intent) > 0) {
                count++;
            }
        }
        return count;
    }

    private static EvidenceIntent detectEvidenceIntent(String userQuestion) {
        String q = searchableText(userQuestion);
        boolean official = containsAny(q,
                "official", "official source", "official-source", "primary source",
                "source of truth", "공식", "1차 출처", "일차 출처");
        boolean changelog = containsAny(q,
                "changelog", "change log", "release note", "release notes", "release-notes",
                "what's new", "whats new", "latest changes", "변경사항", "최신 변경",
                "릴리스", "릴리즈", "출시 노트");
        return new EvidenceIntent(official, changelog);
    }

    private static String noUsableEvidenceAnswer(String userQuestion) {
        EvidenceIntent intent = detectEvidenceIntent(userQuestion);
        String q = searchableText(userQuestion);
        if ((intent != null && intent.officialOrChangelog()) || q.contains("evidence_needed")) {
            TraceStore.put("evidenceAnswerComposer.noUsableEvidence.evidenceNeeded", true);
            return OFFICIAL_NO_RELEVANT_EVIDENCE_ANSWER;
        }
        return NO_RELEVANT_EVIDENCE_ANSWER;
    }

    private static int evidenceIntentScore(EvidenceAwareGuard.EvidenceDoc doc, EvidenceIntent intent) {
        if (doc == null || intent == null || !intent.officialOrChangelog()) {
            return 0;
        }
        String text = searchableEvidenceText(doc);
        int score = 0;
        if (intent.changelog()) {
            if (containsAny(text,
                    "changelog", "change log", "release note", "release notes", "release-notes",
                    "/releases", "releases/", "what's new", "whats new", "변경사항",
                    "릴리스 노트", "릴리즈 노트", "출시 노트")) {
                score += 8;
            }
            if (containsAny(text, "latest", "version", "updates", "update", "최신")) {
                score += 1;
            }
        }
        if (intent.official()) {
            if (containsAny(text,
                    "official", "official docs", "documentation", "api reference",
                    "developer", "developers", "docs.", "/docs", "platform.openai.com/docs",
                    "openai.com/changelog", "learn.microsoft.com", "developer.apple.com",
                    "cloud.google.com", "docs.aws.amazon.com", "docs.github.com")) {
                score += 6;
            }
            if (containsAny(text, "github.com/") && containsAny(text, "/releases", "release")) {
                score += 4;
            }
        }
        boolean communityOrPersonal = containsAny(text,
                "reddit", "stackoverflow", "medium.com", "velog", "tistory",
                "wikipedia", "community", "forum", "personal.", "개인 블로그");
        if (communityOrPersonal) {
            score = Math.min(score - 5, 0);
        }
        return score;
    }

    private static String searchableEvidenceText(EvidenceAwareGuard.EvidenceDoc doc) {
        if (doc == null) {
            return "";
        }
        return searchableText(safe(doc.id(), "") + " "
                + safe(doc.title(), "") + " "
                + safe(doc.snippet(), "") + " "
                + safe(doc.url(), ""));
    }

    private static String searchableText(String raw) {
        return raw == null ? "" : raw.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null || needles.length == 0) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean requestedConditionalHold(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return false;
        }
        if (QUOTED_HOLD.matcher(userQuestion).find()
                || NEGATED_HOLD.matcher(userQuestion).find()
                || POST_HOLD_NEGATION.matcher(userQuestion).find()) {
            return false;
        }
        return CONDITIONAL_HOLD.matcher(userQuestion).find();
    }

    /**
     * Carries only the allowlisted conditional HOLD contract across query rewriting.
     * The original question is inspected but never copied into the resolved question.
     */
    public static String preserveExplicitConditionalHold(
            String resolvedQuestion,
            String originalQuestion) {
        String resolved = resolvedQuestion == null ? "" : resolvedQuestion;
        if (!requestedConditionalHold(originalQuestion) || requestedConditionalHold(resolved)) {
            return resolved;
        }
        String separator = resolved.isBlank()
                || Character.isWhitespace(resolved.charAt(resolved.length() - 1))
                ? ""
                : "\n";
        return resolved + separator + CONDITIONAL_HOLD_CONSTRAINT;
    }

    private record EvidenceIntent(boolean official, boolean changelog) {
        boolean officialOrChangelog() {
            return official || changelog;
        }
    }

    private static void traceInput(String userQuestion, int evidenceCount, boolean lowRiskDomain) {
        String queryHash = SafeRedactor.hash12(userQuestion);
        TraceStore.put("evidenceAnswerComposer.queryHash12", queryHash == null ? "" : queryHash);
        TraceStore.put("evidenceAnswerComposer.queryLength", userQuestion == null ? 0 : userQuestion.length());
        TraceStore.put("evidenceAnswerComposer.evidenceCount", Math.max(0, evidenceCount));
        TraceStore.put("evidenceAnswerComposer.lowRiskDomain", lowRiskDomain);
    }

    private static void traceResult(int usedEvidenceCount,
                                    boolean explanationIncluded,
                                    int evidenceCount,
                                    boolean composed) {
        int safeUsed = Math.max(0, usedEvidenceCount);
        int safeEvidenceCount = Math.max(0, evidenceCount);
        TraceStore.put("evidenceAnswerComposer.composed", composed);
        TraceStore.put("evidenceAnswerComposer.usedEvidenceCount", safeUsed);
        TraceStore.put("evidenceAnswerComposer.unusedEvidenceCount", Math.max(0, safeEvidenceCount - safeUsed));
        TraceStore.put("evidenceAnswerComposer.explanationIncluded", explanationIncluded);
        TraceStore.put("evidenceAnswerComposer.emptyEvidence", safeEvidenceCount == 0);
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
            boolean metadataOnlySnippet = metadataOnlySnippet(doc, snippet);

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
            if (metadataOnlySnippet) {
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

    private static boolean metadataOnlySnippet(EvidenceAwareGuard.EvidenceDoc doc, String snippet) {
        String normalizedSnippet = normalizeForDedupe(snippet);
        if (normalizedSnippet.isBlank()) {
            return true;
        }
        String normalizedTitle = normalizeForDedupe(doc == null ? "" : safe(doc.title(), ""));
        if (!normalizedTitle.isBlank() && normalizedSnippet.equals(normalizedTitle)) {
            return true;
        }
        String lower = normalizedSnippet.toLowerCase();
        return lower.equals("metadata only")
                || lower.startsWith("metadata only ")
                || lower.startsWith("url based ")
                || lower.startsWith("url fallback ")
                || lower.startsWith("we cannot provide a description for this page")
                || lower.contains("description for this page right now url:")
                || lower.matches("https?://\\S+");
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
