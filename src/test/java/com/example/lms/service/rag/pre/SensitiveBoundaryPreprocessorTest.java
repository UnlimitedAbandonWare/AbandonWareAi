package com.example.lms.service.rag.pre;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;

import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SensitiveBoundaryPreprocessorTest {

    @AfterEach
    void clearGuardContext() {
        GuardContextHolder.clear();
    }

    @Test
    void masksEmailBeforeKoreanParticleWithoutShorteningLongTld() {
        SensitiveBoundaryPreprocessor preprocessor = preprocessor(true);

        assertAll(
                () -> assertEquals(
                        "문의는 [EMAIL]으로 보내줘",
                        preprocessor.enrich("문의는 user@example.com으로 보내줘", webMeta())),
                () -> assertEquals(
                        "문의는 [EMAIL]를 확인해줘",
                        preprocessor.enrich("문의는 user@example.company를 확인해줘", webMeta())));
    }

    @Test
    void preservesEmailBoundaryControls() {
        SensitiveBoundaryPreprocessor preprocessor = preprocessor(true);

        assertAll(
                () -> assertEquals(
                        "문의는 [EMAIL] 입니다",
                        preprocessor.enrich("문의는 user@example.com 입니다", webMeta())),
                () -> assertEquals(
                        "문의는 user@example.com2 입니다",
                        preprocessor.enrich("문의는 user@example.com2 입니다", webMeta())),
                () -> assertEquals(
                        "문의는 user@example.com_ 입니다",
                        preprocessor.enrich("문의는 user@example.com_ 입니다", webMeta())),
                () -> assertEquals(
                        "문의는 user@example 입니다",
                        preprocessor.enrich("문의는 user@example 입니다", webMeta())),
                () -> assertEquals(
                        "문의는 [EMAIL], 다음",
                        preprocessor.enrich("문의는 user@example.com, 다음", webMeta())),
                () -> assertEquals(
                        "가user@example.com으로",
                        preprocessor.enrich("가user@example.com으로", webMeta())),
                () -> assertEquals(
                        "user@example.company2",
                        preprocessor.enrich("user@example.company2", webMeta())));
    }

    @Test
    void preservesPurposeEnableMetadataAndPhoneBehavior() {
        SensitiveBoundaryPreprocessor enabled = preprocessor(true);
        Map<String, Object> phoneMeta = webMeta();
        String privateInternalText = "내부 user@example.com 010-1234-5678";
        Map<String, Object> nonWebMeta = new HashMap<>();
        nonWebMeta.put("purpose", "internal_summary");

        assertAll(
                () -> assertEquals(
                        "연락 [PHONE]",
                        enabled.enrich("연락 010-1234-5678", phoneMeta)),
                () -> assertEquals(Boolean.TRUE, phoneMeta.get("privacy.masked")),
                () -> assertEquals(privateInternalText, enabled.enrich(privateInternalText, nonWebMeta)),
                () -> assertFalse(nonWebMeta.containsKey("privacy.masked")),
                () -> assertEquals(
                        privateInternalText,
                        preprocessor(false).enrich(privateInternalText, webMeta())));
    }

    private static SensitiveBoundaryPreprocessor preprocessor(boolean enabled) {
        GuardContextHolder.clear();
        SensitiveBoundaryPreprocessor preprocessor = new SensitiveBoundaryPreprocessor();
        ReflectionTestUtils.setField(preprocessor, "enabled", enabled);
        ReflectionTestUtils.setField(preprocessor, "maxLen", 220);
        return preprocessor;
    }

    private static Map<String, Object> webMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("purpose", "web_search");
        return meta;
    }
}
