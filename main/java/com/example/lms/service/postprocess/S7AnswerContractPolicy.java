package com.example.lms.service.postprocess;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, query-scoped completeness check for the explicit S7 answer contract.
 */
public final class S7AnswerContractPolicy {

    private static final String INCOMPLETE_HOLD =
            "HOLD\n한계: S7 응답 계약이 완전하지 않아 후보 내용을 자동 생성하지 않았습니다.";

    private static final Pattern REWRITE_MARKER = Pattern.compile(
            "(?im)^\\s*(?:재작성|질문\\s*재구성|다시\\s*쓴\\s*질문|rewritten\\s+question|"
                    + "self[-\\s]?ask(?:\\s+rewritten\\s+question)?)\\s*(?:[:：\\-—]|\\b)");
    private static final Pattern EXPLICIT_HOLD_WITH_LIMITATION = Pattern.compile(
            "(?is)^\\s*HOLD\\b.*(?:한계|limitation)\\s*[:：]\\s*\\S.*$");
    private static final Pattern CANDIDATE_HEADER = Pattern.compile(
            "(?im)^\\s*(?:(?:후보|option|candidate)\\s*)?([A-D])"
                    + "(?:\\s*(?:안|후보|option|candidate))?\\s*(?:[.)：:\\-—]|\\b)");
    private static final Pattern VERDICT = Pattern.compile(
            "(?im)^\\s*(?:중립\\s*(?:판정|심판)|독립\\s*심사\\s*(?:결과|판정)|"
                    + "최종\\s*(?:선택|판정)|neutral\\s*verdict)"
                    + "\\s*[:：\\-—]?\\s*(A|B|C|HOLD)\\b");
    private static final Pattern SUPPORT = Pattern.compile(
            "(?i)(지지\\s*근거|장점|support(?:ing)?(?:\\s+(?:evidence|case))?|\\bpro\\b)");
    private static final Pattern COUNTEREXAMPLE = Pattern.compile(
            "(?i)(확인할\\s*반례|반례|위험|리스크|counterexample|\\brisk\\b|\\bcon\\b)");
    private static final Pattern METRIC = Pattern.compile(
            "(?i)(측정\\s*지표|평가\\s*기준|측정값|metric|p95|p99|latency|recall)");
    private static final Pattern NEGATED_FORMAT_REQUEST = Pattern.compile(
            "(?i)(?:형식|구조)\\s*(?:을|를|은|는)?\\s*"
                    + "(?:(?:사용|적용)\\s*하지|쓰\\s*지)\\s*(?:마|말)");
    private static final Pattern ENGLISH_NEGATED_FORMAT_REQUEST = Pattern.compile(
            "(?i)\\b(?:do\\s+not|don['’]t)\\s+(?:use|apply|follow)\\b"
                    + "(?=[^\\r\\n.!?;]{0,180}\\bself[-\\s]?ask\\b)"
                    + "(?=[^\\r\\n.!?;]{0,180}(?:a\\s*/\\s*b\\s*/\\s*c|\\bhold\\b))"
                    + "[^\\r\\n.!?;]{0,200}\\b(?:format|structure)\\b");
    private static final Pattern QUOTED_FORMAT_META_REQUEST = Pattern.compile(
            "(?i)(?:라는|이라고\\s*하는)\\s*(?:형식|구조)(?:이|가|은|는|을|를)?"
                    + "\\s*.{0,24}(?:무엇인지|설명만)");

    public Result evaluate(String query, String answer) {
        if (!isExplicitS7Request(query)) {
            return new Result(answer, false, "not_applicable");
        }
        if (isComplete(answer)) {
            return new Result(answer, false, "complete");
        }
        return new Result(INCOMPLETE_HOLD, true, "incomplete_hold");
    }

    private static boolean isExplicitS7Request(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        if (NEGATED_FORMAT_REQUEST.matcher(normalized).find()
                || ENGLISH_NEGATED_FORMAT_REQUEST.matcher(normalized).find()
                || QUOTED_FORMAT_META_REQUEST.matcher(normalized).find()) {
            return false;
        }
        return containsAny(normalized, "self-ask", "self ask")
                && containsAny(normalized, "a/b/c", "a, b, c", "a,b,c")
                && containsAny(normalized, "지지", "support")
                && containsAny(normalized, "반례", "counterexample")
                && containsAny(normalized, "측정", "metric")
                && containsAny(normalized, "중립", "neutral")
                && normalized.contains("hold");
    }

    private static boolean isComplete(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        if (EXPLICIT_HOLD_WITH_LIMITATION.matcher(answer).matches()) {
            return true;
        }
        if (!REWRITE_MARKER.matcher(answer).find()) {
            return false;
        }

        Matcher verdictMatcher = VERDICT.matcher(answer);
        if (!verdictMatcher.find()) {
            return false;
        }
        String decision = verdictMatcher.group(1).toUpperCase(Locale.ROOT);
        int verdictStart = verdictMatcher.start();
        if (verdictMatcher.find()) {
            return false;
        }

        Matcher headerMatcher = CANDIDATE_HEADER.matcher(answer);
        Map<String, CandidateBlock> candidates = new HashMap<>();
        CandidateBlock previous = null;
        while (headerMatcher.find()) {
            String label = headerMatcher.group(1).toUpperCase(Locale.ROOT);
            if ("D".equals(label) || candidates.containsKey(label) || headerMatcher.start() >= verdictStart) {
                return false;
            }
            if (previous != null) {
                previous.end = headerMatcher.start();
            }
            CandidateBlock current = new CandidateBlock(headerMatcher.end(), verdictStart);
            candidates.put(label, current);
            previous = current;
        }
        if (candidates.size() != 3
                || !candidates.keySet().containsAll(java.util.Set.of("A", "B", "C"))) {
            return false;
        }
        for (CandidateBlock block : candidates.values()) {
            String content = answer.substring(block.start, block.end);
            if (!SUPPORT.matcher(content).find()
                    || !COUNTEREXAMPLE.matcher(content).find()
                    || !METRIC.matcher(content).find()) {
                return false;
            }
        }
        return !"HOLD".equals(decision)
                || Pattern.compile("(?i)(한계|limitation)")
                        .matcher(answer.substring(verdictStart)).find();
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public record Result(String content, boolean changed, String reasonCode) {
    }

    private static final class CandidateBlock {
        private final int start;
        private int end;

        private CandidateBlock(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
