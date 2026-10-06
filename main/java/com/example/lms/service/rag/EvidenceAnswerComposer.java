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
        return compose(userQuestion, evidence, lowRiskDomain, null);
    }

    /**
     * contextSubject(분석된 현재 토픽 또는 세션 carry 토픽)를 받는 확장형.
     * 질문 키워드/컨텍스트 주제와 전혀 정합하지 않는 근거는 답변으로 채택하지 않고
     * {@link #noUsableEvidenceAnswer} 를 반환한다 — 모델 장애 시 무관한 검색
     * 결과가 그럴듯한 답으로 둔갑하는 것을 막는 마지막 게이트다.
     */
    public String compose(String userQuestion,
                          List<EvidenceAwareGuard.EvidenceDoc> evidence,
                          boolean lowRiskDomain,
                          String contextSubject) {

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

        // 정합성 게이트: 분석/carry 로 확보된 컨텍스트 주제가 있을 때만 발동한다.
        // 주제 신호 없는 질문은 "무관함"을 판정할 앵커 자체가 없으므로 기존
        // 동작(근거 나열)을 유지한다 — 범용 질문+범용 문서까지 차단해 정상
        // 근거 답변을 죽이지 않기 위함이다. 전량 탈락이면 noUsableEvidenceAnswer
        // 를 반환해 상위에서 degraded 응답으로 바꾼다.
        boolean alignmentJudgeable = contextSubject != null && !contextSubject.isBlank();
        List<EvidenceAwareGuard.EvidenceDoc> alignedEvidence =
                filterAlignedEvidence(usableEvidence, userQuestion, contextSubject);
        TraceStore.put("evidenceAnswerComposer.alignmentJudgeable", alignmentJudgeable);
        TraceStore.put("evidenceAnswerComposer.alignedEvidenceCount", alignedEvidence.size());
        if (alignmentJudgeable) {
            if (alignedEvidence.isEmpty()) {
                TraceStore.put("evidenceAnswerComposer.alignmentRejected", true);
                traceResult(0, false, evidenceCount, false);
                return noUsableEvidenceAnswer(userQuestion);
            }
            usableEvidence = alignedEvidence;
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
            String title = displayTitle(doc.title());
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

    /**
     * 근거 문서가 질문/컨텍스트 주제와 정합하는지 결정론적으로 판정한다.
     * contextSubject 가 없으면 호출되지 않는다(판정 앵커 부재).
     * 정합 기준: contextSubject 부분일치, 또는 강한 키워드(3자 이상) 1개 이상,
     * 또는 약한 키워드(2자) min(2, 키워드 수)개 이상. 약한 단독 히트는
     * 일반명사 우연 일치를 근거로 채택하지 않기 위한 완충이다.
     */
    private static List<EvidenceAwareGuard.EvidenceDoc> filterAlignedEvidence(
            List<EvidenceAwareGuard.EvidenceDoc> usable,
            String userQuestion,
            String contextSubject) {
        if (usable == null || usable.isEmpty()) {
            return usable == null ? List.of() : usable;
        }
        List<String> keywords = extractKeywords(userQuestion);
        String subject = contextSubject == null ? "" : contextSubject.trim().toLowerCase();
        int weakNeed = Math.min(2, keywords.size());
        List<EvidenceAwareGuard.EvidenceDoc> aligned = new ArrayList<>();
        for (EvidenceAwareGuard.EvidenceDoc doc : usable) {
            if (doc == null) {
                continue;
            }
            String text = searchableEvidenceText(doc);
            if (!subject.isBlank() && text.contains(subject)) {
                aligned.add(doc);
                continue;
            }
            int strong = 0;
            int weak = 0;
            for (String kw : keywords) {
                if (kw == null || kw.isBlank() || !text.contains(kw)) {
                    continue;
                }
                if (kw.length() >= 3) {
                    strong++;
                } else {
                    weak++;
                }
            }
            if (strong >= 1 || (weakNeed > 0 && weak >= weakNeed)) {
                aligned.add(doc);
            }
        }
        return aligned;
    }

    /**
     * compose 결과가 "근거 부적합/부재" 고정 응답인지 판별한다.
     * 호출 측은 이 결과를 근거 답변으로 채택하지 않고 degraded 응답으로 전환한다.
     */
    public static boolean isNoRelevantEvidenceAnswer(String answer) {
        String a = answer == null ? "" : answer.trim();
        return a.equals(NO_RELEVANT_EVIDENCE_ANSWER)
                || a.equals(OFFICIAL_NO_RELEVANT_EVIDENCE_ANSWER);
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

    // 진행 중 상태를 나타내는 과도기 제목(Loading... 등)은 근거 제목으로 렌더링하지 않는다.
    private static final Pattern TRANSIENT_TITLE = Pattern.compile(
            "(?i)^\\s*(?:loading|로딩\\s*중|로딩중|fetching|검색\\s*중)[.…\\s]*$");

    private static String displayTitle(String title) {
        String t = safe(title, "");
        return TRANSIENT_TITLE.matcher(t).matches() ? "제목 없음" : t;
    }
    /** A bounded quotation, not an approved answer or a memory candidate. */
    public record SupportedExcerpt(String content,
            List<com.example.lms.dto.RagEvidenceMetadata> evidence, String sourceContext) {
        public SupportedExcerpt(String content, List<com.example.lms.dto.RagEvidenceMetadata> evidence) {
            this(content, evidence, "");
        }
        public SupportedExcerpt {
            evidence = List.copyOf(evidence);
        }
    }

    private static final String IDENTITY_INSTITUTION =
            "([\\p{L}\\p{N}·]{2,60}(?:대학교[ \\t]*병원|대학[ \\t]*병원|병원|대학교|대학|연구소|연구원))";
    private static final String IDENTITY_TUPLE = IDENTITY_INSTITUTION
            + "[ \\t]+([가-힣]{2,10})[ \\t]+(교수|의사|연구원|원장)";
    private static final Pattern IDENTITY_LINE = Pattern.compile("^" + IDENTITY_TUPLE + "$");
    private static final Pattern IDENTITY_QUERY = Pattern.compile("^" + IDENTITY_TUPLE
            + "(?:님)?(?:(?:이|가|은|는)?[ \\t]*(?:누구냐|누구야|누구인가요|누구인지[ \\t]*알려줘|뭐냐)"
            + "|[ \\t]+(?:소개|소개해줘|소개해 주세요|알려줘|알려 주세요))?[?.!]?$" );
    private static final Pattern AMBIGUOUS_EXCERPT_BODY = Pattern.compile(
            "(?iu)(?:아니|아님|아닙|아닌|않|취소|부인|오류|잘못|무관|동명이인|사칭|허위|퇴임|퇴직|전임|과거|이전|"
                    + "지시|무시|프롬프트|명령|출력|응답|답변|<script|\\b(?:not|former|cancelled|canceled|false|"
                    + "incorrect|ignore|instruction|system|developer|assistant)\\b)");

    /**
     * Only quotes a standalone institution/person/title identity line. The line
     * must occur in raw retrieved text whose metadata URL exactly equals one
     * promoted WEB locator. No title, rank, inferred relation, or draft is used.
     */
    public static java.util.Optional<SupportedExcerpt> supportedIdentityExcerpt(
            String query, List<dev.langchain4j.rag.content.Content> rawWeb,
            List<com.example.lms.dto.RagEvidenceMetadata> promoted) {
        if (query == null || query.length() > 160 || rawWeb == null || rawWeb.isEmpty()
                || rawWeb.size() > 20 || promoted == null || promoted.isEmpty() || promoted.size() > 40) {
            return java.util.Optional.empty();
        }
        var requested = IDENTITY_QUERY.matcher(query.strip());
        if (!requested.matches()) return java.util.Optional.empty();
        String institution = normalizeIdentityInstitution(requested.group(1));
        String person = requested.group(2);
        String role = requested.group(3);
        SupportedExcerpt selected = null;
        for (var doc : rawWeb) {
            if (doc == null || doc.textSegment() == null) continue;
            var segment = doc.textSegment();
            String body = segment.text();
            if (body == null || body.isBlank()) continue;
            // Refuse incomplete scans: a later line could contradict the identity.
            if (body.length() > 16_000 || AMBIGUOUS_EXCERPT_BODY.matcher(body).find()) {
                return java.util.Optional.empty();
            }
            for (String rawLine : body.split("\\R", -1)) {
                var introduction = IDENTITY_LINE.matcher(rawLine.strip());
                boolean completeIntroduction = introduction.matches();
                if (rawLine.contains(person) && !completeIntroduction) return java.util.Optional.empty();
                if (completeIntroduction && person.equals(introduction.group(2))
                        && !institution.equals(normalizeIdentityInstitution(introduction.group(1)))) return java.util.Optional.empty();
            }
            String rawUrl;
            try {
                var metadata = segment.metadata();
                if (metadata == null) continue;
                String kind = metadata.getString("kind");
                if ((kind != null && !"WEB".equals(kind)) || metadata.getString("filePath") != null
                        || metadata.getString("attachmentId") != null || metadata.getString("sourceId") != null
                        || metadata.getString("owner") != null || metadata.getString("private") != null) {
                    return java.util.Optional.empty();
                }
                if (metadata.getString("_nova.compressed") != null
                        || metadata.getString("_nova.origHash") != null) return java.util.Optional.empty();
                rawUrl = metadata.getString("url");
                String source = metadata.getString("source");
                if (rawUrl == null) rawUrl = source;
                else if (source != null && !rawUrl.equals(source)) return java.util.Optional.empty();
            } catch (RuntimeException malformedMetadata) {
                return java.util.Optional.empty();
            }
            rawUrl = RagEvidenceAttributionService.sanitizePublicUrl(rawUrl);
            if (!isPublicExcerptUrl(rawUrl)) continue;
            com.example.lms.dto.RagEvidenceMetadata locator = null;
            for (var item : promoted) {
                if (item == null || !"WEB".equals(item.kind()) || item.attachment() != null
                        || !rawUrl.equals(item.source()) || item.marker() == null
                        || !item.marker().matches("W[1-9][0-9]{0,3}")) continue;
                if (locator != null) return java.util.Optional.empty();
                locator = item;
            }
            for (String rawLine : body.split("\\R", -1)) {
                String line = rawLine.strip();
                var introduction = IDENTITY_LINE.matcher(line);
                if (!introduction.matches()) continue;
                if (locator == null || !institution.equals(normalizeIdentityInstitution(introduction.group(1)))
                        || !person.equals(introduction.group(2)) || !role.equals(introduction.group(3))) continue;
                // strip() preserves a contiguous source span; no compression or paraphrase.
                if (selected == null) {
                    String content = "[UNVERIFIED · 검증 미완료 / 원문 일부]\n\n> " + line
                            + "\n\n[" + locator.marker() + "](" + rawUrl + ")";
                    selected = new SupportedExcerpt(content, List.of(locator));
                }
            }
        }
        return java.util.Optional.ofNullable(selected);
    }

    private static final String DESCRIPTION_DOMAIN = "^([\\p{L}\\p{N}·]{2,30}?)(?:에서|에)\\s+";
    private static final String DESCRIPTION_ENTITY = "([\\p{L}\\p{N}·]{2,24}?)";
    private static final Pattern DESCRIPTION_QUERY = Pattern.compile(DESCRIPTION_DOMAIN + DESCRIPTION_ENTITY
            + "(?:이|가|은|는)?\\s*(?:뭐냐|뭐야|무엇인가요|알려줘|소개해줘)[?.!]?$");
    private static final Pattern ALTERNATIVE_DESCRIPTION_QUERY = Pattern.compile(DESCRIPTION_DOMAIN
            + DESCRIPTION_ENTITY + "인가\\s*" + DESCRIPTION_ENTITY
            + "인가\\s*(?:그게\\s*)?(?:뭐냐|뭐야|무엇인가요)[?.!]?$");
    private static final Pattern COMPARISON_DESCRIPTION_QUERY = Pattern.compile(DESCRIPTION_DOMAIN
            + DESCRIPTION_ENTITY + "(?:이|가|은|는)?\\s*(?:쎄냐|세냐)\\??\\s*"
            + DESCRIPTION_ENTITY + "(?:이|가|은|는)?\\s*(?:쎄냐|세냐)[?.!]?$");
    private static final Pattern DESCRIPTION_LINE = Pattern.compile(
            "^([\\p{L}\\p{N}·]{2,24})(?:\\(별칭[ :]+([\\p{L}\\p{N}·]{2,24})\\))?"
                    + "(?:은|는|이|가)\\s+(.{8,380}[.!?])$");

    /** Direct public-source descriptions; spelling similarity never confirms identity. */
    public static java.util.Optional<SupportedExcerpt> supportedDescriptionExcerpt(
            String query, List<dev.langchain4j.rag.content.Content> rawWeb,
            List<com.example.lms.dto.RagEvidenceMetadata> promoted) {
        if (query == null || query.length() > 160 || com.example.lms.search.SearchQueryConstraints.hasConstraints(query)
                || rawWeb == null || rawWeb.isEmpty() || rawWeb.size() > 20
                || promoted == null || promoted.isEmpty() || promoted.size() > 40) return java.util.Optional.empty();
        Set<String> markers = new HashSet<>();
        for (var item : promoted) {
            if (item != null && "WEB".equals(item.kind()) && item.marker() != null
                    && !markers.add(item.marker())) return java.util.Optional.empty();
        }
        var request = DESCRIPTION_QUERY.matcher(query.strip());
        boolean comparison = false, alternatives = false;
        if (!request.matches()) {
            request = COMPARISON_DESCRIPTION_QUERY.matcher(query.strip());
            comparison = request.matches();
            if (!comparison) {
                request = ALTERNATIVE_DESCRIPTION_QUERY.matcher(query.strip());
                alternatives = request.matches();
                if (!alternatives) return java.util.Optional.empty();
            }
        }
        String domain = request.group(1);
        List<String> entities = comparison || alternatives
                ? List.of(request.group(2), request.group(3)) : List.of(request.group(2));
        Pattern domainAnchor = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(domain)
                + "(?=에서|에|의|은|는|[\\s·,:.!?]|$)");
        Pattern domainRelationship = Pattern.compile("^" + Pattern.quote(domain)
                + "(?:의\\s+|에(?:서)?\\s+등장(?:하는|한)\\s+|\\s+버전\\s+[\\p{L}\\p{N}.]+에서\\s+)");
        var lines = new java.util.LinkedHashMap<String, String>();
        var citations = new java.util.LinkedHashMap<String, com.example.lms.dto.RagEvidenceMetadata>();
        String identifiedSubject = null;
        for (var doc : rawWeb) {
            if (doc == null || doc.textSegment() == null) continue;
            String body = doc.textSegment().text();
            if (body == null || body.isBlank()) continue;
            // Do not select a safe-looking line from a contradictory/instruction-bearing document.
            if (body.length() > 16_000 || AMBIGUOUS_EXCERPT_BODY.matcher(body).find()
                    || body.indexOf('<') >= 0 || body.indexOf('>') >= 0) return java.util.Optional.empty();
            var locator = descriptionLocator(doc, promoted);
            if (locator == null) continue;
            for (String rawLine : body.split("\\R", -1)) {
                String line = rawLine.strip();
                var subject = DESCRIPTION_LINE.matcher(line);
                if (!subject.matches() || !domainAnchor.matcher(line).find()
                        || !domainRelationship.matcher(subject.group(3)).find()
                        || line.matches(".*[\\[\\]*_].*") || line.indexOf((char) 96) >= 0 || line.contains("://")) continue;
                for (String entity : entities) {
                    if (!entity.equals(subject.group(1)) && !entity.equals(subject.group(2))) continue;
                    if (!comparison && identifiedSubject != null && !identifiedSubject.equals(subject.group(1)))
                        return java.util.Optional.empty();
                    identifiedSubject = subject.group(1);
                    lines.putIfAbsent(entity, line);
                    citations.putIfAbsent(entity, locator);
                    break;
                }
            }
        }
        if (lines.isEmpty()) return java.util.Optional.empty();
        StringBuilder content = new StringBuilder("[UNVERIFIED · 검증 미완료 / 자료의 직접 설명]\n");
        StringBuilder sourceContext = new StringBuilder();
        var used = new java.util.LinkedHashMap<String, com.example.lms.dto.RagEvidenceMetadata>();
        for (String entity : entities) {
            String line = lines.get(entity);
            if (line == null) {
                if (comparison) content.append("\n").append(entity).append(": 같은 대상의 설명 근거를 찾지 못했습니다.\n");
                continue;
            }
            var locator = citations.get(entity);
            content.append("\n> ").append(line).append("\n\n[").append(locator.marker()).append("](")
                    .append(locator.source()).append(")\n");
            sourceContext.append("[").append(locator.marker()).append("](").append(locator.source())
                    .append(")\n").append(line).append("\n");
            used.putIfAbsent(locator.marker(), locator);
            if (!comparison) break;
        }
        if (comparison) content.append("\n각 자료의 조건을 그대로 제시했습니다. 동일 조건의 성능 측정 근거가 없어 우열은 확인되지 않았습니다.");
        return java.util.Optional.of(new SupportedExcerpt(content.toString(), List.copyOf(used.values()),
                sourceContext.toString()));
    }

    private static com.example.lms.dto.RagEvidenceMetadata descriptionLocator(
            dev.langchain4j.rag.content.Content doc, List<com.example.lms.dto.RagEvidenceMetadata> promoted) {
        try {
            var metadata = doc.textSegment().metadata();
            if (metadata == null) return null;
            String kind = metadata.getString("kind");
            if (kind != null && !"WEB".equals(kind)) return null;
            for (String key : List.of("filePath", "attachmentId", "sourceId", "owner", "private",
                    "_nova.compressed", "_nova.origHash")) {
                if (metadata.getString(key) != null) return null;
            }
            String url = metadata.getString("url"), source = metadata.getString("source");
            if (url == null) url = source;
            else if (source != null && !url.equals(source)) return null;
            url = RagEvidenceAttributionService.sanitizePublicUrl(url);
            if (!isPublicExcerptUrl(url)) return null;
            com.example.lms.dto.RagEvidenceMetadata selected = null;
            for (var item : promoted) {
                if (item == null || !"WEB".equals(item.kind()) || item.attachment() != null
                        || !url.equals(item.source()) || item.marker() == null
                        || !item.marker().matches("W[1-9][0-9]{0,3}")) continue;
                if (selected != null) return null;
                selected = item;
            }
            return selected;
        } catch (RuntimeException invalidMetadata) { return null; }
    }

    private static String normalizeIdentityInstitution(String institution) {
        // This spelling-only normalization must not merge arbitrary entity tokens.
        return institution.replaceAll("(대학교|대학)[ \\t]+병원", "$1병원");
    }

    private static boolean isPublicExcerptUrl(String value) {
        if (value == null || value.length() > 2_000 || value.matches(".*[\\s<>\"()\\[\\]{}].*")) return false;
        try {
            var uri = java.net.URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("https".equals(scheme) || "http".equals(scheme)) || host == null
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            // DNS names only: no IP literals, local/reserved hosts, or network probes.
            return host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}")
                    && !host.endsWith(".localhost") && !host.endsWith(".local")
                    && !host.endsWith(".internal") && !host.endsWith(".test")
                    && !host.endsWith(".invalid") && !host.endsWith(".example");
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }

}
