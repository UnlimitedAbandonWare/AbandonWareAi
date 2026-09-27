package com.example.lms.service.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorQualityGuardKoreanBoundaryTest {

    private VectorQualityGuard guard;
    private Method estimateUnknownRatio;
    private Method unknownPattern;

    @BeforeEach
    void setUp() throws Exception {
        guard = new VectorQualityGuard();

        Field tokens = VectorQualityGuard.class.getDeclaredField("unknownValueTokensCsv");
        tokens.setAccessible(true);
        tokens.set(guard, "unknown,unconfirmed,n/a,na,none,미상,불명,정보없음");

        estimateUnknownRatio = VectorQualityGuard.class.getDeclaredMethod(
                "estimateUnknownRatioFromText", String.class);
        estimateUnknownRatio.setAccessible(true);

        unknownPattern = VectorQualityGuard.class.getDeclaredMethod("unknownPattern");
        unknownPattern.setAccessible(true);
    }

    @Test
    void recognizesOnlyBoundedUnknownTokensAndKoreanCopulas() throws Exception {
        assertRatio(1.0, "상태: 미상");
        assertRatio(1.0, "상태: 미상입니다");
        assertRatio(1.0, "상태: 미상이다");
        assertRatio(1.0, "상태: 미상임");
        assertRatio(1.0, "상태: 미상입니다.");
        assertRatio(1.0, "상태: \"미상입니다\".");
        assertRatio(1.0, "상태: 불명입니다");
        assertRatio(1.0, "status: unknown");
        assertRatio(1.0, "status: unknown입니다");
        assertRatio(1.0, "status: n/a");
        assertRatio(1.0, "status: n/a입니다");

        assertCapturedBase("미상입니다", "미상");
        assertCapturedBase("unknown입니다", "unknown");
        assertCapturedBase("n/a입니다", "n/a");
    }

    @Test
    void rejectsLexicalIdentifierAndCombiningMarkContinuations() throws Exception {
        assertRatio(0.0, "평가: 불명예");
        assertRatio(0.0, "평가: 불명예입니다");
        assertRatio(0.0, "평가: 미상정보");
        assertRatio(0.0, "평가: 이미상입니다");
        assertRatio(0.0, "평가: x미상입니다");
        assertRatio(0.0, "평가: 미상입니다만");
        assertRatio(0.0, "평가: 미상이다운");
        assertRatio(0.0, "평가: 미상_값");
        assertRatio(0.0, "평가: 미상2");
        assertRatio(0.0, "평가: 미상\u0301");
        assertRatio(0.0, "평가: 미상입니다\u0301");
        assertRatio(0.0, "status: unknowns");
        assertRatio(0.0, "status: xunknown");
        assertRatio(0.0, "status: unknown_value");
        assertRatio(0.0, "status: n/ax");
        assertRatio(0.0, "status: xn/a");
    }

    private void assertRatio(double expected, String line) throws Exception {
        double actual = ((Number) estimateUnknownRatio.invoke(guard, line)).doubleValue();
        assertEquals(expected, actual, 0.000001, () -> "ratio mismatch for: " + line);
    }

    private void assertCapturedBase(String input, String expectedBase) throws Exception {
        Pattern pattern = (Pattern) unknownPattern.invoke(guard);
        Matcher matcher = pattern.matcher(input);
        assertTrue(matcher.find(), () -> "expected a match for: " + input);
        assertEquals(expectedBase, matcher.group(1));
        assertEquals(expectedBase, matcher.group(), "copula suffix must remain unconsumed");
    }
}
