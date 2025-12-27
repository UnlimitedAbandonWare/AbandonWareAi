package ai.abandonware.nova.orch.failpattern;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FailurePatternDetectorSourceRegressionTest {

    @Test
    void detect_shouldClassifyChatDraftKeyAsLlmSource() {
        var detector = new FailurePatternDetector();

        // Observed pattern (regression): key=chat:draft.. was misclassified as web.
        var msg = "[NightmareBreaker] OPEN key=chat:draft:qwen2.5-7b-instruct kind=HTTP_4XX status=404 remaining=2";

        var match = detector.detect("com.example.lms.aux.NightmareBreaker", msg);
        assertTrue(match.isPresent());
        assertEquals("llm", match.get().source());
        assertEquals("chat:draft:qwen2.5-7b-instruct", match.get().key());
    }
}
