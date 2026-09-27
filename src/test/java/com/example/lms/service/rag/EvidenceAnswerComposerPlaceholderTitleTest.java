package com.example.lms.service.rag;

import com.example.lms.service.guard.EvidenceAwareGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 56 cycle-02: 과도기 제목(Loading... 등)이 근거 제목으로 렌더링되지 않는지 검증.
 */
class EvidenceAnswerComposerPlaceholderTitleTest {

    private final EvidenceAnswerComposer composer = new EvidenceAnswerComposer();

    private static EvidenceAwareGuard.EvidenceDoc doc(String title) {
        return new EvidenceAwareGuard.EvidenceDoc(
                "doc-1", title, "실제 근거가 되는 스니펫 본문입니다.", "https://example.com/doc-1");
    }

    @Test
    void loadingEllipsisTitleIsNotRenderedAsEvidenceTitle() {
        String answer = composer.compose("기능 설명해줘", List.of(doc("Loading...")), false);

        assertFalse(answer.contains("Loading..."), "transient title must not surface: " + answer);
        assertTrue(answer.contains("제목 없음"), "fallback title expected: " + answer);
    }

    @Test
    void loadingVariantsAreNormalized() {
        for (String title : List.of("Loading", "loading ..", " 로딩 중 ", "로딩중…")) {
            String answer = composer.compose("기능 설명해줘", List.of(doc(title)), false);

            assertFalse(answer.contains("- **" + title + "**"),
                    "transient title must not surface: " + title + " -> " + answer);
        }
    }

    @Test
    void realTitlesStillRender() {
        String answer = composer.compose("기능 설명해줘",
                List.of(doc("실제 문서 제목")), false);

        assertTrue(answer.contains("실제 문서 제목"), "real title must render: " + answer);
    }
}
