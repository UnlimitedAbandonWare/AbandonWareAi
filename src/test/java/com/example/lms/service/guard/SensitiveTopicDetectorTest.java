package com.example.lms.service.guard;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveTopicDetectorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void marksSensitiveTopicWithTagOnlyTrace() {
        SensitiveTopicDetector detector = new SensitiveTopicDetector();
        ReflectionTestUtils.setField(detector, "enabled", true);
        ReflectionTestUtils.setField(detector, "answerTemp", 0.2d);
        ReflectionTestUtils.setField(detector, "exploreTempCap", 0.7d);
        GuardContext guardContext = new GuardContext();
        ChatRequestDto request = ChatRequestDto.builder()
                .message("PTSD contact user.name@example.com api_key=raw-api-value")
                .build();

        detector.applyTo(guardContext, request);

        assertTrue(guardContext.isSensitiveTopic());
        assertEquals(Boolean.TRUE, guardContext.getPlanOverride("memory.forceOff"));
        assertEquals(Boolean.TRUE, guardContext.getPlanOverride("privacy.boundary.mask-web-query"));
        assertEquals(Boolean.TRUE, TraceStore.get("privacy.sensitive"));
        assertEquals("[trauma]", String.valueOf(TraceStore.get("privacy.sensitive.tags")));
        assertEquals(0.2d, guardContext.planDouble("llm.answer.temperature.max", 2.0d));
        assertEquals(0.7d, guardContext.planDouble("llm.selfAsk.temperature.max", 2.0d));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("user.name@example.com"), trace);
        assertFalse(trace.contains("raw-api-value"), trace);
    }

    @Test
    void sensitiveCapsKeepTheLowerValueIndependentOfWriteOrder() {
        SensitiveTopicDetector detector = new SensitiveTopicDetector();
        ReflectionTestUtils.setField(detector, "enabled", true);
        ReflectionTestUtils.setField(detector, "answerTemp", 0.2d);
        ReflectionTestUtils.setField(detector, "exploreTempCap", 0.7d);
        GuardContext guardContext = new GuardContext();
        guardContext.putPlanOverride("llm.answer.temperature.max", 0.15d);

        detector.applyTo(guardContext, ChatRequestDto.builder().message("PTSD support").build());
        guardContext.putPlanOverride("llm.answer.temperature", 1.5d);

        assertEquals(0.15d, guardContext.planDouble("llm.answer.temperature.max", 2.0d));
        assertEquals(1.5d, guardContext.planDouble("llm.answer.temperature", 0.0d));
    }

    @Test
    void englishSensitiveTermsAreCaseInsensitiveAndBounded() {
        SensitiveTopicDetector detector = new SensitiveTopicDetector();
        ReflectionTestUtils.setField(detector, "enabled", true);
        ReflectionTestUtils.setField(detector, "answerTemp", 0.2d);
        ReflectionTestUtils.setField(detector, "exploreTempCap", 0.7d);

        for (String message : java.util.List.of(
                "ptsd support", "self-harm support", "suicide prevention", "abuse recovery",
                "가정 폭력 지원", "성 폭력 지원")) {
            GuardContext context = new GuardContext();
            detector.applyTo(context, ChatRequestDto.builder().message(message).build());
            assertTrue(context.isSensitiveTopic(), message);
        }

        GuardContext ordinary = new GuardContext();
        detector.applyTo(ordinary, ChatRequestDto.builder()
                .message("a resourceful story about artifacts").build());
        assertFalse(ordinary.isSensitiveTopic());
    }

    @Test
    void sensitiveTopicDetectorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/guard/SensitiveTopicDetector.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Sensitive topic fail-soft trace writes need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }
}
