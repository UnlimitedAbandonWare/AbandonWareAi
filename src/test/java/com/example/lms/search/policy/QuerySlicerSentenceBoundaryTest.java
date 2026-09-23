package com.example.lms.search.policy;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QuerySlicerSentenceBoundaryTest {
    @Test
    void slicesAdjacentJapaneseSentencesWithoutSpaces() {
        assertEquals(List.of("最初の質問です。", "次の質問ですよ。"),
                QuerySlicer.slice("最初の質問です。次の質問ですよ。", 1, 0, 3));
    }

    @Test
    void slicesFullWidthQuestionsAndExclamationsWithOptionalSpaces() {
        assertEquals(List.of("最初の質問ですよ？", "次の質問ですよ！", "最後の質問ですよ。"),
                QuerySlicer.slice("最初の質問ですよ？次の質問ですよ！  最後の質問ですよ。", 1, 0, 3));
    }

    @Test
    void preservesLatinDecimalsAndNewlineBoundaries() {
        assertEquals(List.of("Value is 3.14.", "Next question?", "Final question!"),
                QuerySlicer.slice("Value is 3.14. Next question?\nFinal question!", 1, 0, 5));
        assertEquals(List.of(), QuerySlicer.slice("Version 3.14 remains valid", 1, 0, 3));
    }

    @Test
    void retainsOverlapMaximumAndEmptyInputContracts() {
        assertEquals(List.of("First sentence. Second sentence.", "Second sentence. Third sentence."),
                QuerySlicer.slice("First sentence. Second sentence. Third sentence. Fourth sentence.", 2, 1, 2));
        assertEquals(List.of(), QuerySlicer.slice(null, 1, 0, 3));
        assertEquals(List.of(), QuerySlicer.slice("Only one sentence.", 1, 0, 3));
    }
}
