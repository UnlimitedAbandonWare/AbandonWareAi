package ai.abandonware.nova.orch.compress;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorTailQueryCompressorUnicodeBoundaryTest {

    @Test
    void rawTailFallbackDoesNotBeginWithHalfOfASupplementaryCharacter() {
        String original = "```" + ".".repeat(7) + "\uD83D\uDE00" + "!".repeat(7);

        AnchorTailQueryCompressor.Result result =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(original, 8, null);

        assertAll(
                () -> assertEquals(AnchorTailQueryCompressor.PickStrategy.RAW_TAIL, result.strategy()),
                () -> assertTrue(result.condensed().length() <= 8),
                () -> assertEquals("!".repeat(7), result.condensed()),
                () -> assertFalse(hasUnpairedSurrogate(result.condensed())));
    }

    @Test
    void publicTinyBudgetsUseAnEllipsisInsteadOfHalfASupplementaryCharacter() {
        String original = ".".repeat(10) + "\uD83D\uDE00";

        String oneUnit = AnchorTailQueryCompressor
                .condenseKeepAnchorAndTail(original, 1, null)
                .condensed();
        String twoUnits = AnchorTailQueryCompressor
                .condenseKeepAnchorAndTail(original, 2, null)
                .condensed();

        assertAll(
                () -> assertEquals("\u2026", oneUnit),
                () -> assertEquals("\u2026", twoUnits));
    }

    @Test
    void publicCondenseDoesNotStartRetainedTailWithHalfOfASurrogatePair() {
        String original = ".".repeat(10) + "\uD83D\uDE00" + "!".repeat(6);

        AnchorTailQueryCompressor.Result result =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(original, 8, null);

        assertAll(
                () -> assertEquals(AnchorTailQueryCompressor.PickStrategy.LAST_NON_EMPTY_LINE,
                        result.strategy()),
                () -> assertTrue(result.condensed().length() <= 8),
                () -> assertTrue(result.condensed().startsWith("\u2026")),
                () -> assertFalse(hasUnpairedSurrogate(result.condensed())));
    }

    @Test
    void keepEndLimitPreservesCompletePairsAndLegacyLengthControls() throws Exception {
        String straddling = truncateKeepEnd(".".repeat(10) + "\uD83D\uDE00" + "!".repeat(6), 8);
        String completePair = truncateKeepEnd(".".repeat(10) + "\uD83D\uDE00" + "!".repeat(5), 8);

        assertAll(
                () -> assertFalse(hasUnpairedSurrogate(straddling)),
                () -> assertTrue(straddling.length() <= 8),
                () -> assertFalse(hasUnpairedSurrogate(completePair)),
                () -> assertEquals(8, completePair.length()),
                () -> assertEquals("\u2026abcdefg", truncateKeepEnd(".".repeat(20) + "abcdefg", 8)),
                () -> assertEquals("abcdefgh", truncateKeepEnd("abcdefgh", 8)),
                () -> assertEquals("y", truncateKeepEnd("xy", 1)));
    }

    @Test
    void endScanWindowDoesNotBeginWithHalfOfASupplementaryCharacter() {
        String original = "\uD83D\uDE00" + ".".repeat(7_999);

        AnchorTailQueryCompressor.Result result =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(original, 8_000, null);

        assertAll(
                () -> assertEquals(AnchorTailQueryCompressor.PickStrategy.LAST_NON_EMPTY_LINE,
                        result.strategy()),
                () -> assertTrue(result.condensed().length() <= 8_000),
                () -> assertFalse(hasUnpairedSurrogate(result.condensed())));
    }

    @Test
    void fallbackAnchorPrefixDoesNotEndWithHalfOfASupplementaryCharacter() {
        String token = "A".repeat(47) + new String(Character.toChars(0x10400)) + "B".repeat(10);

        AnchorTailQueryCompressor.Result result =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(token, 1_000, null);

        assertAll(
                () -> assertTrue(result.anchor().length() <= 48),
                () -> assertFalse(hasUnpairedSurrogate(result.anchor())));
    }

    @Test
    void headScanWindowDoesNotEndWithHalfOfASupplementaryCharacter() {
        String beforePair = ".".repeat(3_401) + "\n" + "please? " + "A".repeat(589);
        String original = beforePair + "\uD83D\uDE00" + ".".repeat(5_000);

        AnchorTailQueryCompressor.Result result =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(original, 8_000, null);

        assertAll(
                () -> assertEquals(AnchorTailQueryCompressor.PickStrategy.HEAD_REQUEST_LINE,
                        result.strategy()),
                () -> assertTrue(result.condensed().length() <= 8_000),
                () -> assertFalse(hasUnpairedSurrogate(result.pickedLine())),
                () -> assertFalse(hasUnpairedSurrogate(result.condensed())));
    }

    @Test
    void narrowerLastResortAnchorDoesNotExposeHalfOfASupplementaryCharacter() {
        String original = "!".repeat(27)
                + "\uD83D\uDE00"
                + "?".repeat(400)
                + "\nplease?";
        AnchorNarrower narrower = new AnchorNarrower();

        AnchorTailQueryCompressor.Result keep =
                AnchorTailQueryCompressor.condenseKeepAnchorAndTail(original, 256, narrower);
        AnchorTailQueryCompressor.Result analysis =
                AnchorTailQueryCompressor.condenseForQueryAnalysis(original, 256, narrower);

        assertAll(
                () -> assertEquals("!".repeat(27), keep.anchor()),
                () -> assertEquals("!".repeat(27), analysis.anchor()),
                () -> assertFalse(hasUnpairedSurrogate(keep.anchor())),
                () -> assertFalse(hasUnpairedSurrogate(keep.condensed())),
                () -> assertFalse(hasUnpairedSurrogate(analysis.anchor())),
                () -> assertFalse(hasUnpairedSurrogate(analysis.condensed())));
    }

    private static String truncateKeepEnd(String value, int maxLen) throws Exception {
        Method method = AnchorTailQueryCompressor.class.getDeclaredMethod(
                "truncateKeepEnd", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value, maxLen);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
