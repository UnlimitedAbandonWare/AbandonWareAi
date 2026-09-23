package com.example.lms.guard;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/** Deterministic, provider-free classifier for the current request only. */
@Component
public final class ConversationFrameResolver {

    private static final int MAX_CLASSIFICATION_CHARS = 4_096;

    private static final List<String> META_MARKERS = List.of(
            "번역", "영어로", "무슨 뜻", "인용", "예시", "정책", "문서에서",
            "제3자", "친구가", "라고 말했다", "말했다고", "보고했",
            "translate", "translation", "quote", "example", "policy",
            "third party", "my friend", "someone said");

    private static final List<String> SAFETY_NEGATIONS = List.of(
            "죽고 싶지 않", "죽을 생각 없", "자살할 생각 없", "자해할 생각 없",
            "나를 해칠 생각 없", "내 몸을 해칠 생각 없",
            "do not want to die", "don't want to die", "not suicidal",
            "no intention to harm myself", "will not hurt myself");

    private static final List<String> IMMEDIATE_SAFETY_MARKERS = List.of(
            "죽고 싶", "죽어버리고 싶", "자살하고 싶", "자살할 것 같",
            "자해할 것 같", "나를 해칠 것 같", "내 몸을 해칠 것 같",
            "지금 뛰어내리", "want to die", "kill myself", "hurt myself",
            "end my life", "about to harm myself", "suicidal right now");

    private static final List<String> STOP_NEGATIONS = List.of(
            "멈추지 말", "그만두지 말", "중단하지 말", "분석을 멈추지 말",
            "don't stop", "do not stop", "keep going", "continue explaining");

    private static final List<String> EXPLICIT_STOP_MARKERS = List.of(
            "그만", "멈춰", "중단해", "분석하지 마", "분석을 중단",
            "더 말하지 마", "여기까지", "stop", "pause", "don't analyze",
            "do not analyze", "leave it there");

    private static final List<String> BOUNDARY_COMPLAINT_MARKERS = List.of(
            "내 경계를 무시", "경계를 무시", "계속 분석해서 불편",
            "계속 분석해서 상처", "네가 계속 분석", "불편했어", "상처 줬",
            "ignored my boundary", "kept analyzing", "made me uncomfortable",
            "you hurt me");

    private static final List<String> DISTRESS_MARKERS = List.of(
            "너무 지쳤", "마음이 너무 힘들", "버티기 힘들", "혼자인 느낌",
            "너무 외로", "압도돼", "압도된", "상처받", "감당하기 힘들",
            "i'm exhausted", "i am exhausted", "i feel alone", "i'm overwhelmed",
            "i am overwhelmed", "i'm hurt", "i am hurt", "i can't cope");

    public ConversationFrameV1 resolve(
            String userText,
            boolean multimodalInputPresent,
            ConversationFrameV1.Mode requestedMode) {
        ConversationFrameV1.Mode mode = requestedMode == null
                ? ConversationFrameV1.Mode.OFF
                : requestedMode;
        String normalized = normalize(userText);
        Classification classification = classify(normalized);
        return new ConversationFrameV1(
                mode,
                classification.stance(),
                ConversationFrameV1.LightweightRole.OBSERVE_ONLY,
                multimodalInputPresent,
                false,
                false,
                classification.reasonCode());
    }

    private static Classification classify(String normalized) {
        if (normalized.isBlank()) {
            return Classification.standard();
        }

        boolean metaContext = containsAny(normalized, META_MARKERS);
        if (!metaContext
                && !containsAny(normalized, SAFETY_NEGATIONS)
                && containsAny(normalized, IMMEDIATE_SAFETY_MARKERS)) {
            return new Classification(
                    ConversationFrameV1.Stance.SAFETY_FIRST,
                    ConversationFrameV1.ReasonCode.IMMEDIATE_SAFETY_SIGNAL);
        }

        if (!metaContext
                && !containsAny(normalized, STOP_NEGATIONS)
                && containsAny(normalized, EXPLICIT_STOP_MARKERS)) {
            return new Classification(
                    ConversationFrameV1.Stance.REPAIR,
                    ConversationFrameV1.ReasonCode.EXPLICIT_STOP);
        }

        if (!metaContext && containsAny(normalized, BOUNDARY_COMPLAINT_MARKERS)) {
            return new Classification(
                    ConversationFrameV1.Stance.REPAIR,
                    ConversationFrameV1.ReasonCode.ASSISTANT_BOUNDARY_COMPLAINT);
        }

        if (!metaContext && containsAny(normalized, DISTRESS_MARKERS)) {
            return new Classification(
                    ConversationFrameV1.Stance.SUPPORTIVE_CHECK_IN,
                    ConversationFrameV1.ReasonCode.DISTRESS_CHECK_IN);
        }
        return Classification.standard();
    }

    private static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        StringBuilder withoutFormatCharacters = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
                .forEach(withoutFormatCharacters::appendCodePoint);
        String collapsed = withoutFormatCharacters.toString()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (collapsed.length() <= MAX_CLASSIFICATION_CHARS) {
            return collapsed;
        }
        int headLength = MAX_CLASSIFICATION_CHARS / 2;
        int tailLength = MAX_CLASSIFICATION_CHARS - headLength - 1;
        return collapsed.substring(0, headLength)
                + " "
                + collapsed.substring(collapsed.length() - tailLength);
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private record Classification(
            ConversationFrameV1.Stance stance,
            ConversationFrameV1.ReasonCode reasonCode) {
        private static Classification standard() {
            return new Classification(
                    ConversationFrameV1.Stance.STANDARD,
                    ConversationFrameV1.ReasonCode.DEFAULT);
        }
    }
}
