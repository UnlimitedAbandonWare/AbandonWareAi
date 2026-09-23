package com.example.lms.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, request-local parser for an explicit missing-evidence output contract. */
public final class EvidenceNeededDirectivePolicy {
    private static final String TOKEN_REGEX =
            "(?<![A-Za-z0-9_])evidence_needed(?![A-Za-z0-9_])";
    private static final Pattern TOKEN = Pattern.compile(TOKEN_REGEX, Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSING_EVIDENCE_CONDITION = Pattern.compile(
            "(?:\\b(?:if|when)\\b[^\\r\\n.!?;。！？；]{0,180}(?:"
                    + "(?:no|missing|absent|unavailable|insufficient|without|not\\s+enough)\\s+"
                    + "(?:reliable\\s+)?(?:evidence|sources?|citations?|proof)"
                    + "|(?:the\\s+)?(?:evidence|sources?|citations?|proof)"
                    + "(?:\\s+(?:is|are))?\\s+"
                    + "(?:missing|absent|unavailable|insufficient|not\\s+found|cannot\\s+be\\s+found))"
                    + "|(?:(?:근거|증거|출처|자료)(?:가|이|를|을)?[^\\r\\n.!?;。！？；]{0,48}"
                    + "(?:없으면|없는\\s*경우|부족하면|부족한\\s*경우|찾지\\s*못하면|"
                    + "확인할\\s*수\\s*없으면|확보되지\\s*않으면)))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTPUT_DIRECTIVE = Pattern.compile(
            "(?:\\b(?:answer|reply|respond|return|say|output|emit|use)\\b[^\\r\\n.!?;。！？；]{0,48}"
                    + TOKEN_REGEX
                    + "|" + TOKEN_REGEX + "[^\\r\\n.!?;。！？；]{0,48}"
                    + "\\b(?:answer|reply|respond|return|say|output|emit|use)\\b"
                    + "|(?:답|응답|말|출력|반환|표시|사용|쓰)[^\\r\\n.!?;。！？；]{0,32}"
                    + TOKEN_REGEX
                    + "|" + TOKEN_REGEX
                    + "[^\\r\\n.!?;。！？；]{0,32}(?:답|응답|말|출력|반환|표시|사용|쓰))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATION = Pattern.compile(
            "(?:\\b(?:do\\s+not|don't|never|must\\s+not|should\\s+not)\\s+"
                    + "(?:answer|reply|respond|return|say|output|emit|use)\\b"
                    + "[^\\r\\n.!?;。！？；]{0,48}" + TOKEN_REGEX
                    + "|\\bavoid\\b[^\\r\\n.!?;。！？；]{0,32}" + TOKEN_REGEX
                    + "|" + TOKEN_REGEX + "[^\\r\\n.!?;。！？；]{0,48}"
                    + "(?:\\b(?:must\\s+not|should\\s+not)\\s+(?:be\\s+)?"
                    + "(?:answered|returned|used|said|output|emitted)\\b"
                    + "|\\b(?:do\\s+not|don't|never|avoid)\\s+"
                    + "(?:answer|reply|respond|return|say|output|emit|use)\\b)"
                    + "|" + TOKEN_REGEX + "[^\\r\\n.!?;。！？；]{0,40}"
                    + "(?:답변|답|응답|말|출력|반환|표시|사용|쓰|처리)하지(?:는|도)?\\s*(?:마|말|않)"
                    + "|(?:답변|답|응답|말|출력|반환|표시|사용|쓰|처리)(?:은|는)?"
                    + "[^\\r\\n.!?;。！？；]{0,32}" + TOKEN_REGEX
                    + "[^\\r\\n.!?;。！？；]{0,16}하지(?:는|도)?\\s*(?:마|말|않)"
                    + "|(?:답변|답|응답|말|출력|반환|표시|사용|쓰|처리)하지(?:는|도)?[^\\r\\n]{0,48}"
                    + TOKEN_REGEX + ")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_REQUEST = Pattern.compile(
            "(?:\\b(?:translate|explain|discuss|quote|paraphrase|analy[sz]e|define|describe|test)\\b"
                    + "[^\\r\\n.!?;。！？；]{0,96}" + TOKEN_REGEX
                    + "|(?:번역|설명|정의|분석|토론|인용문|예시|테스트)"
                    + "[^\\r\\n.!?;。！？；]{0,96}" + TOKEN_REGEX
                    + "|" + TOKEN_REGEX + "[^\\r\\n]{0,32}"
                    + "(?:means?|meaning|term|phrase|뜻|의미|표현|문구|규칙))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTPUT_TOKEN_REJECTION = Pattern.compile(
            "\\b(?:answer|reply|respond|return|say|output|emit|use)\\b"
                    + "[^\\r\\n.!?;]{0,64}"
                    + "\\b(?:not|anything\\s+but|other\\s+than|instead\\s+of|rather\\s+than|except|but\\s+not)\\s+"
                    + TOKEN_REGEX,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_RULE_REQUEST = Pattern.compile(
            "\\b(?:review|compare|evaluate|critique)\\b"
                    + "[^\\r\\n.!?;]{0,48}"
                    + "\\b(?:this|the)\\s+(?:instruction|rule|directive|phrase|request)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDITIONAL_CONDITIONAL_IN_GAP = Pattern.compile(
            "\\b(?:if|when|unless|except|otherwise)\\b",
            Pattern.CASE_INSENSITIVE);

    private EvidenceNeededDirectivePolicy() {
    }

    public static boolean requiresEvidenceNeeded(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }
        Matcher conditionMatcher = MISSING_EVIDENCE_CONDITION.matcher(userQuery);
        while (conditionMatcher.find()) {
            if (insidePairedQuote(userQuery, conditionMatcher.start())) {
                continue;
            }
            Matcher directiveMatcher = OUTPUT_DIRECTIVE.matcher(userQuery);
            while (directiveMatcher.find()) {
                Matcher tokenMatcher = TOKEN.matcher(userQuery);
                while (tokenMatcher.find()) {
                    int tokenOffset = tokenMatcher.start();
                    if (tokenOffset < directiveMatcher.start() || tokenOffset >= directiveMatcher.end()
                            || insidePairedQuote(userQuery, tokenOffset)) {
                        continue;
                    }
                    if (sameConditionalClause(
                            userQuery,
                            conditionMatcher.start(),
                            conditionMatcher.end(),
                            tokenMatcher.start(),
                            tokenMatcher.end())
                            && !clauseRejected(
                                    userQuery,
                                    Math.min(conditionMatcher.start(), directiveMatcher.start()),
                                    Math.max(conditionMatcher.end(), directiveMatcher.end()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean sameConditionalClause(
            String value,
            int conditionStart,
            int conditionEnd,
            int tokenStart,
            int tokenEnd) {
        int gapStart;
        int gapEnd;
        if (tokenStart >= conditionEnd) {
            gapStart = conditionEnd;
            gapEnd = tokenStart;
        } else if (conditionStart >= tokenEnd) {
            gapStart = tokenEnd;
            gapEnd = conditionStart;
        } else {
            return true;
        }
        if (gapEnd - gapStart > 240) {
            return false;
        }
        for (int i = gapStart; i < gapEnd; i++) {
            if (isClauseBoundary(value.charAt(i))) {
                return false;
            }
        }
        return !ADDITIONAL_CONDITIONAL_IN_GAP.matcher(value.substring(gapStart, gapEnd)).find();
    }

    private static boolean clauseRejected(String value, int spanStart, int spanEnd) {
        int clauseStart = Math.max(0, spanStart);
        while (clauseStart > 0 && !isClauseBoundary(value.charAt(clauseStart - 1))) {
            clauseStart--;
        }
        int clauseEnd = Math.min(value.length(), spanEnd);
        while (clauseEnd < value.length() && !isClauseBoundary(value.charAt(clauseEnd))) {
            clauseEnd++;
        }
        String clause = value.substring(clauseStart, clauseEnd);
        return NEGATION.matcher(clause).find()
                || OUTPUT_TOKEN_REJECTION.matcher(clause).find()
                || META_RULE_REQUEST.matcher(clause).find()
                || META_REQUEST.matcher(clause).find();
    }

    private static boolean isClauseBoundary(char c) {
        return c == '\r' || c == '\n' || c == '.' || c == '!' || c == '?' || c == ';'
                || c == '。' || c == '！' || c == '？' || c == '；';
    }

    private static boolean insidePairedQuote(String value, int offset) {
        return insideSameDelimiter(value, offset, '"')
                || insideSingleQuoteSpan(value, offset)
                || insideBacktickSpan(value, offset)
                || insideDelimitedRange(value, offset, '‘', '’')
                || insideDelimitedRange(value, offset, '“', '”');
    }

    private static boolean insideSameDelimiter(String value, int offset, char delimiter) {
        int count = 0;
        for (int i = 0; i < offset && i < value.length(); i++) {
            if (value.charAt(i) == delimiter && (i == 0 || value.charAt(i - 1) != '\\')) {
                count++;
            }
        }
        return (count & 1) == 1 && value.indexOf(delimiter, Math.max(0, offset)) >= 0;
    }

    private static boolean insideSingleQuoteSpan(String value, int offset) {
        int openIndex = -1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '\'' || apostropheWithinWord(value, i)) {
                continue;
            }
            if (openIndex < 0) {
                boolean canOpen = i + 1 < value.length()
                        && !Character.isWhitespace(value.charAt(i + 1))
                        && (i == 0 || !Character.isLetterOrDigit(value.charAt(i - 1)));
                if (canOpen) {
                    openIndex = i;
                }
                continue;
            }
            boolean canClose = i > 0
                    && !Character.isWhitespace(value.charAt(i - 1))
                    && (i + 1 == value.length() || !Character.isLetterOrDigit(value.charAt(i + 1)));
            if (canClose) {
                if (offset > openIndex && offset < i) {
                    return true;
                }
                openIndex = -1;
            }
        }
        return false;
    }

    private static boolean apostropheWithinWord(String value, int index) {
        return index > 0
                && index + 1 < value.length()
                && Character.isLetterOrDigit(value.charAt(index - 1))
                && Character.isLetterOrDigit(value.charAt(index + 1));
    }

    private static boolean insideBacktickSpan(String value, int offset) {
        int openRunLength = 0;
        int contentStart = -1;
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) != '`') {
                i++;
                continue;
            }
            int runStart = i;
            while (i < value.length() && value.charAt(i) == '`') {
                i++;
            }
            int runLength = i - runStart;
            if (openRunLength == 0) {
                openRunLength = runLength;
                contentStart = i;
            } else if (runLength == openRunLength) {
                if (offset >= contentStart && offset < runStart) {
                    return true;
                }
                openRunLength = 0;
                contentStart = -1;
            }
        }
        return false;
    }

    private static boolean insideDelimitedRange(String value, int offset, char open, char close) {
        int openIndex = value.lastIndexOf(open, offset);
        if (openIndex < 0) {
            return false;
        }
        int firstCloseAfterOpen = value.indexOf(close, openIndex + 1);
        return firstCloseAfterOpen >= offset;
    }
}
