package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAttachmentQuestionDetectorTest {

    @Test
    void detectsAttachmentLanguageAndFileExtensions() {
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("Please summarize the uploaded document"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("첨부 파일 확인해줘"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("read report.pdf"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("deck.pptx\uB97C summarize"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("brief.hwpx\uC5D0 comments"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("photo.webp\uB97C analyze"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("파일내용을 요약해줘"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("첨부파일내용을 요약해줘"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("첨부파일좀 확인해줘"));
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("전자문서를 요약해줘"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("hello there"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("   "));
    }

    @Test
    void rejectsEnglishMarkerSubstringsAndZipCodePhrase() {
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("update my profile"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("recommend a documentary"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("what is the zip code"));
    }

    @Test
    void rejectsKoreanMarkerLexicalContinuations() {
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("파일럿 프로젝트"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("문서화 전략"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("전자문서법 개정"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("파일내용물 분석"));
    }

    @Test
    void detectsHangulObjectParticleAfterDocxExtension() {
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx를 요약해줘"));
    }

    @Test
    void detectsHangulObjectParticleAfterShorterDocExtension() {
        assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.doc를 요약해줘"));
    }

    @Test
    void detectsEveryDeclaredHangulParticleAfterExtension() {
        String[] particles = {
                "은", "는", "이", "가", "을", "를", "와", "과", "의",
                "에", "로", "으로", "에서", "에게", "부터", "까지", "만", "도"
        };

        for (String particle : particles) {
            assertTrue(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion(
                    "archive.docx" + particle + " 확인"), "particle=" + particle);
        }
    }

    @Test
    void rejectsLexicalContinuationsAfterExtensionOrParticle() {
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docxmalware"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx_foo"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docxé"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx악성"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx를foo"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx\u0301"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx를\u0301"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx\u200Dfoo"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx\u200Cfoo"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx\u203Ffoo"));
        assertFalse(ChatAttachmentQuestionDetector.looksLikeAttachmentQuestion("report.docx에서도"));
    }

    @Test
    void controllerPassesAttachmentPresenceSeparatelyFromQuestionClassification() {
        ChatRequestDto attachedPlainQuestion = ChatRequestDto.builder()
                .message("hello there")
                .attachmentIds(List.of("attachment-1"))
                .build();
        ChatRequestDto noAttachments = attachedPlainQuestion.toBuilder()
                .attachmentIds(List.of())
                .build();

        assertTrue(ChatApiController.hasAttachments(attachedPlainQuestion));
        assertFalse(ChatApiController.hasAttachments(noAttachments));
        assertFalse(ChatApiController.hasAttachments(null));
    }
}
