package com.example.lms.search.extract;

import com.example.lms.service.QueryAugmentationService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridKeywordExtractorUnicodeBoundaryTest {

    @Test
    void publicExtractReturnsAValidPromptPoseSeed() throws Exception {
        String rawSeed = "a".repeat(127) + "\uD83D\uDE00tail";
        String assistantDraft = "PromptPose search seed hints:\n\"" + rawSeed + "\"";
        HybridKeywordExtractor extractor = new HybridKeywordExtractor(
                new QueryAugmentationService(), null, null, null);
        Field extractorMode = HybridKeywordExtractor.class.getDeclaredField("extractorMode");
        extractorMode.setAccessible(true);
        extractorMode.set(extractor, "RULE");

        List<String> extracted = extractor.extract("plain", assistantDraft, "anchor", "GENERAL", 8, 0.99d);

        assertFalse(extracted.isEmpty());
        String first = extracted.get(0);
        assertAll(
                () -> assertTrue(first.length() <= 128),
                () -> assertEquals("a".repeat(127), first.substring(0, 127)),
                () -> assertFalse(hasUnpairedSurrogate(first)));
    }

    @Test
    void promptPoseSeedLimitDoesNotSplitSurrogatePairs() throws Exception {
        String straddling = cleanPromptPoseSeed("a".repeat(127) + "\uD83D\uDE00tail");
        String completePair = cleanPromptPoseSeed("b".repeat(126) + "\uD83D\uDE00tail");
        String bmpOverflow = cleanPromptPoseSeed("c".repeat(129));
        String exactLimit = cleanPromptPoseSeed("d".repeat(128));

        assertAll(
                () -> assertFalse(hasUnpairedSurrogate(straddling)),
                () -> assertTrue(straddling.length() <= 128),
                () -> assertEquals("a".repeat(127), straddling.substring(0, 127)),
                () -> assertFalse(hasUnpairedSurrogate(completePair)),
                () -> assertEquals(128, completePair.length()),
                () -> assertEquals("c".repeat(128), bmpOverflow),
                () -> assertEquals("d".repeat(128), exactLimit));
    }

    private static String cleanPromptPoseSeed(String raw) throws Exception {
        Method method = HybridKeywordExtractor.class.getDeclaredMethod("cleanPromptPoseSeed", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, raw);
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
