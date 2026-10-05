package com.example.lms.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure literal answers extracted from the workflow without changing matching rules. */
public final class DeterministicLiteralAnswers {

    private DeterministicLiteralAnswers() {
    }

    static String numberOnlyMultiplication(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean numberOnly = text.contains("\uC22B\uC790\uB9CC")
                || lower.contains("number only")
                || lower.contains("only the number");
        boolean numberOnlyNegated = Pattern.compile(
                "\\b(?:(?:do\\s+not|don't|dont)\\s+(?:answer|respond|give)(?:\\s+with)?\\s+"
                        + "|not\\s+)(?:only\\s+the\\s+number|number\\s+only)\\b")
                .matcher(lower)
                .find();
        boolean arithmeticQuestion = text.contains("\uC5BC\uB9C8")
                || text.contains("\uACC4\uC0B0")
                || lower.contains("what is")
                || lower.contains("calculate");
        if (!numberOnly || numberOnlyNegated || !arithmeticQuestion) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?<![\\p{L}\\p{N}.,+\\-*/^%\\u00d7xX])"
                + "([+-]?\\d{1,9})\\s*(?:[\\u00d7xX*]|\\uACF1\\uD558\\uAE30)\\s*([+-]?\\d{1,9})"
                        + "(?![\\p{N}A-Za-z+\\-*/^%\\u00d7xX]|[.,]\\d)")
                .matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String beforeExpression = text.substring(0, matcher.start());
        String afterExpression = text.substring(matcher.end());
        boolean chainedBefore = Pattern.compile(
                "[\\d)]\\s*[+\\-*/^%\\u00d7xX]\\s*\\(*\\s*$")
                .matcher(beforeExpression)
                .find();
        boolean chainedAfter = Pattern.compile(
                "^\\s*\\)*\\s*[+\\-*/^%\\u00d7xX]\\s*\\(*\\s*[+\\-]?\\d")
                .matcher(afterExpression)
                .find();
        if (chainedBefore || chainedAfter) {
            return null;
        }
        long left = Long.parseLong(matcher.group(1));
        long right = Long.parseLong(matcher.group(2));
        if (matcher.find()) {
            return null;
        }
        return String.valueOf(Math.multiplyExact(left, right));
    }
}
