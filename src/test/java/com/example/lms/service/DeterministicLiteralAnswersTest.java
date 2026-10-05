package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DeterministicLiteralAnswersTest {

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("multiplicationCases")
    void preservesNumberOnlyMultiplication(String scenario, String input, String expected) {
        assertEquals(expected, DeterministicLiteralAnswers.numberOnlyMultiplication(input));
    }

    static Stream<Arguments> multiplicationCases() {
        return Stream.of(
                Arguments.of("English multiplication", "what is 3x4, only the number", "12"),
                Arguments.of("Korean multiplication", "17 \uacf1\ud558\uae30 23\uc744 \uacc4\uc0b0\ud574. \uc22b\uc790\ub9cc", "391"),
                Arguments.of("ASCII whitespace", "calculate 3\t*\n4, number only", "12"),
                Arguments.of("multiplication sign", "calculate 3\u00d74, only the number", "12"),
                Arguments.of("uppercase marker", "WHAT IS 3X4, NUMBER ONLY", "12"),
                Arguments.of("signed operands", "calculate -3 * +4, only the number", "-12"),
                Arguments.of("two negative operands", "calculate -3 * -4, only the number", "12"),
                Arguments.of("leading zeros", "calculate 003 * 004, only the number", "12"),
                Arguments.of("zero product", "calculate 0 * -4, only the number", "0"),
                Arguments.of("nine-digit limit", "calculate 999999999 * 999999999, only the number", "999999998000000001"),
                Arguments.of("oversized operand", "calculate 9999999999 * 2, only the number", null),
                Arguments.of("long overflow operands rejected", "calculate 9223372036854775807 * 2, only the number", null),
                Arguments.of("full-width digits", "calculate \uff13 * \uff14, only the number", null),
                Arguments.of("full-width operator", "calculate 3\uff0a4, only the number", null),
                Arguments.of("full-width spaces", "calculate 3\u3000*\u30004, only the number", null),
                Arguments.of("addition", "calculate 3 + 4, only the number", null),
                Arguments.of("no arithmetic question", "3x4, only the number", null),
                Arguments.of("no number-only directive", "what is 3x4", null),
                Arguments.of("negated directive", "calculate 3x4, do not answer with only the number", null),
                Arguments.of("negated words", "calculate 3x4, not words, only the number", "12"),
                Arguments.of("decimal operand", "calculate 3.5x4, only the number", null),
                Arguments.of("chained before", "calculate 3 + 5x4, only the number", null),
                Arguments.of("chained after", "calculate (3x4) + 5, only the number", null),
                Arguments.of("multiple expressions", "calculate 3x4 and 5x6, only the number", null),
                Arguments.of("Latin suffix", "calculate 3x4abc, only the number", null),
                Arguments.of("empty input", "", null),
                Arguments.of("blank input", " \t\n", null),
                Arguments.of("null input", null, null));
    }
}
