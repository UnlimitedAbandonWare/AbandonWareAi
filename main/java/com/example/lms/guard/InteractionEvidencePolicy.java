package com.example.lms.guard;

import java.text.Normalizer;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.VisionMode;

/**
 * Pure, request-scoped interaction policy whose social-style axis is independent
 * from evidence handling.
 *
 * <p>The policy accepts only bounded observations and proof facts. It never owns
 * raw requests, retrieved text, identity, session state, clocks, providers, or
 * long-term storage.</p>
 */
public final class InteractionEvidencePolicy {

    private static final Pattern FORMAT_CHARACTERS = Pattern.compile("\\p{Cf}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COOPERATION_CUE = Pattern.compile(
            "(?:\\blet(?:'s| us)\\b|\\bwork together\\b|\\bcollaborat(?:e|ion|ively)\\b|"
                    + "함께|같이\\s*(?:해|보|진행)|협업)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern META_DISCUSSION = Pattern.compile(
            "(?:\\b(?:analy[sz]e|explain|discuss|describe|review|quote|quoted|classify|translate|"
                    + "educational|example|why)\\b|분석|설명|논의|검토|인용|번역|교육|예시)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXECUTION_TRANSITION = Pattern.compile(
            "(?:[.!?]\\s*|\\b(?:now|then|next|but|however|instead)\\b|이제|그다음|하지만|대신)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PROTECTED_BOUNDARY_DIRECTIVE = Pattern.compile(
            "(?:(?:please|kindly)\\s+)?(?:"
                    + "(?:ignore|disregard|override|bypass|forget)\\b.{0,120}"
                    + "(?:previous|prior|system|developer)\\b.{0,80}(?:instructions?|prompt|message|rules?)|"
                    + "(?:reveal|show|expose|print)\\b.{0,100}(?:system|developer)\\b.{0,60}(?:prompt|message|instructions?)|"
                    + "(?:무시|덮어쓰|우회|잊어).{0,100}(?:이전|시스템|개발자).{0,60}(?:지시|프롬프트|메시지|규칙)|"
                    + "(?:공개|보여|노출).{0,80}(?:시스템|개발자).{0,50}(?:프롬프트|메시지|지시))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final Pattern EVIDENCE_MANIPULATION_DIRECTIVE = Pattern.compile(
            "(?:(?:please|kindly)\\s+)?(?:"
                    + "(?:hide|omit|suppress|conceal|remove|fabricate|invent|fake|falsify|forge)\\b.{0,60}"
                    + "(?:citations?|sources?|evidence|references?|provenance)|"
                    + "(?:숨기|감추|빼|조작|위조|날조|꾸며).{0,40}(?:출처|인용|근거|증거|반증))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final Pattern COERCIVE_OVERRIDE = Pattern.compile(
            "(?:\\bor else\\b|\\botherwise\\b|\\bi (?:will|'ll).{0,40}(?:report|punish|harm|fire|sue)\\b|"
                    + "(?:안 하면|하지 않으면).{0,40}(?:신고|해고|고소|벌|해치))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    private InteractionEvidencePolicy() {
    }

    public enum ResponseStyle {
        STANDARD,
        COLLABORATIVE
    }

    public enum SecurityStance {
        NEUTRAL,
        DEFENSIVE
    }

    public enum EvidenceMode {
        BASELINE,
        STRICT
    }

    public enum MemoryWriteMode {
        NORMAL,
        SUPPRESS
    }

    public enum FailureMode {
        LEGACY_FAIL_SOFT,
        FAIL_CLOSED
    }

    public enum FeatureMode {
        OFF,
        SHADOW,
        ENFORCE;

        public static FeatureMode parse(String value) {
            if (value == null) {
                return OFF;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "off" -> OFF;
                case "shadow" -> SHADOW;
                case "enforce" -> ENFORCE;
                default -> OFF;
            };
        }
    }

    public enum ManipulationKind {
        PROMPT_INJECTION,
        EVIDENCE_TAMPERING,
        UNAUTHORIZED_ACCESS,
        COERCIVE_OVERRIDE
    }

    public enum ProofKind {
        PROTECTED_BOUNDARY_EXECUTION,
        DIGEST_MISMATCH,
        PROVENANCE_MISMATCH,
        AUTHORIZATION_DENIED
    }

    public enum DetectorRule {
        REQUEST_PROTECTED_BOUNDARY_V1,
        REQUEST_EVIDENCE_MANIPULATION_V1,
        REQUEST_COERCIVE_OVERRIDE_V1,
        EVIDENCE_DIGEST_V1,
        EVIDENCE_PROVENANCE_V1,
        SESSION_AUTHORIZATION_V1
    }

    public enum SourceSurface {
        USER_REQUEST,
        RETRIEVED_EVIDENCE,
        SESSION_HISTORY,
        TOOL_OUTPUT
    }

    /** A bounded proof fact. No member can hold raw interaction data. */
    public record ManipulationFact(
            ManipulationKind kind,
            ProofKind proofKind,
            DetectorRule detectorRule,
            SourceSurface sourceSurface) {

        public ManipulationFact {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(proofKind, "proofKind");
            Objects.requireNonNull(detectorRule, "detectorRule");
            Objects.requireNonNull(sourceSurface, "sourceSurface");
        }
    }

    /** Immutable input to policy evaluation. */
    public record InteractionObservation(
            boolean explicitCooperationCue,
            List<ManipulationFact> confirmedManipulationFacts) {

        public InteractionObservation {
            List<ManipulationFact> safe = confirmedManipulationFacts == null
                    ? List.of()
                    : confirmedManipulationFacts.stream()
                            .filter(Objects::nonNull)
                            .distinct()
                            .sorted(FACT_ORDER)
                            .toList();
            confirmedManipulationFacts = safe;
        }

        public static InteractionObservation neutral() {
            return new InteractionObservation(false, List.of());
        }

        public static InteractionObservation cooperative() {
            return new InteractionObservation(true, List.of());
        }

        public static InteractionObservation withFacts(ManipulationFact... facts) {
            return new InteractionObservation(false, facts == null ? List.of() : List.of(facts));
        }

        public InteractionObservation withAdditionalFacts(List<ManipulationFact> additionalFacts) {
            if (additionalFacts == null || additionalFacts.isEmpty()) {
                return this;
            }
            java.util.ArrayList<ManipulationFact> merged = new java.util.ArrayList<>(confirmedManipulationFacts);
            merged.addAll(additionalFacts);
            return new InteractionObservation(explicitCooperationCue, merged);
        }
    }

    private static final Comparator<ManipulationFact> FACT_ORDER = Comparator
            .comparing(ManipulationFact::kind)
            .thenComparing(ManipulationFact::proofKind)
            .thenComparing(ManipulationFact::detectorRule)
            .thenComparing(ManipulationFact::sourceSurface);

    /**
     * Deterministically derives only bounded request facts. Quoted, translated,
     * educational, or analytic attack discussion is not execution proof.
     */
    public static InteractionObservation observeRequest(String request) {
        String normalized = normalize(request);
        boolean cooperation = COOPERATION_CUE.matcher(normalized).find();
        if (normalized.isBlank()) {
            return new InteractionObservation(cooperation, List.of());
        }

        String executableText = maskQuotedSegments(normalized);
        java.util.regex.Matcher protectedMatcher = PROTECTED_BOUNDARY_DIRECTIVE.matcher(executableText);
        java.util.regex.Matcher evidenceMatcher = EVIDENCE_MANIPULATION_DIRECTIVE.matcher(executableText);
        boolean protectedDirective = protectedMatcher.find();
        boolean evidenceDirective = evidenceMatcher.find();
        int firstDirectiveStart = firstDirectiveStart(protectedMatcher, protectedDirective,
                evidenceMatcher, evidenceDirective);
        if (firstDirectiveStart < 0 || isMetaOnlyDiscussion(executableText, firstDirectiveStart)) {
            return new InteractionObservation(cooperation, List.of());
        }

        java.util.ArrayList<ManipulationFact> facts = new java.util.ArrayList<>();
        if (protectedDirective) {
            facts.add(new ManipulationFact(
                    ManipulationKind.PROMPT_INJECTION,
                    ProofKind.PROTECTED_BOUNDARY_EXECUTION,
                    DetectorRule.REQUEST_PROTECTED_BOUNDARY_V1,
                    SourceSurface.USER_REQUEST));
        }
        if (evidenceDirective) {
            facts.add(new ManipulationFact(
                    ManipulationKind.EVIDENCE_TAMPERING,
                    ProofKind.PROTECTED_BOUNDARY_EXECUTION,
                    DetectorRule.REQUEST_EVIDENCE_MANIPULATION_V1,
                    SourceSurface.USER_REQUEST));
        }
        if (!facts.isEmpty() && COERCIVE_OVERRIDE.matcher(executableText).find()) {
            facts.add(new ManipulationFact(
                    ManipulationKind.COERCIVE_OVERRIDE,
                    ProofKind.PROTECTED_BOUNDARY_EXECUTION,
                    DetectorRule.REQUEST_COERCIVE_OVERRIDE_V1,
                    SourceSurface.USER_REQUEST));
        }
        return new InteractionObservation(cooperation, facts);
    }

    private static int firstDirectiveStart(
            java.util.regex.Matcher protectedMatcher,
            boolean protectedDirective,
            java.util.regex.Matcher evidenceMatcher,
            boolean evidenceDirective) {
        int protectedStart = protectedDirective ? protectedMatcher.start() : Integer.MAX_VALUE;
        int evidenceStart = evidenceDirective ? evidenceMatcher.start() : Integer.MAX_VALUE;
        int first = Math.min(protectedStart, evidenceStart);
        return first == Integer.MAX_VALUE ? -1 : first;
    }

    private static boolean isMetaOnlyDiscussion(String text, int directiveStart) {
        if (directiveStart <= 0) {
            return false;
        }
        java.util.regex.Matcher meta = META_DISCUSSION.matcher(text.substring(0, directiveStart));
        int lastMetaEnd = -1;
        while (meta.find()) {
            lastMetaEnd = meta.end();
        }
        if (lastMetaEnd < 0) {
            return false;
        }
        String afterLastMeta = text.substring(lastMetaEnd, directiveStart);
        return !EXECUTION_TRANSITION.matcher(afterLastMeta).find();
    }

    private static String maskQuotedSegments(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        StringBuilder masked = new StringBuilder(text.length());
        char activeQuote = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (activeQuote == 0 && (current == '\'' || current == '"' || current == '`')
                    && text.indexOf(current, i + 1) > i) {
                activeQuote = current;
                masked.append(' ');
            } else if (activeQuote != 0) {
                masked.append(' ');
                if (current == activeQuote) {
                    activeQuote = 0;
                }
            } else {
                masked.append(current);
            }
        }
        return masked.toString();
    }

    public static Decision evaluate(InteractionObservation observation, FeatureMode featureMode) {
        FeatureMode safeMode = featureMode == null ? FeatureMode.OFF : featureMode;
        InteractionObservation safeObservation = observation == null
                ? InteractionObservation.neutral()
                : observation;
        if (safeMode == FeatureMode.OFF) {
            return offDecision();
        }

        List<ManipulationFact> facts = safeObservation.confirmedManipulationFacts();
        boolean defensive = !facts.isEmpty();
        return new Decision(
                safeMode,
                safeObservation.explicitCooperationCue() ? ResponseStyle.COLLABORATIVE : ResponseStyle.STANDARD,
                defensive ? SecurityStance.DEFENSIVE : SecurityStance.NEUTRAL,
                defensive ? EvidenceMode.STRICT : EvidenceMode.BASELINE,
                defensive ? MemoryWriteMode.SUPPRESS : MemoryWriteMode.NORMAL,
                defensive ? FailureMode.FAIL_CLOSED : FailureMode.LEGACY_FAIL_SOFT,
                facts);
    }

    public static Decision offDecision() {
        return new Decision(
                FeatureMode.OFF,
                ResponseStyle.STANDARD,
                SecurityStance.NEUTRAL,
                EvidenceMode.BASELINE,
                MemoryWriteMode.NORMAL,
                FailureMode.LEGACY_FAIL_SOFT,
                List.of());
    }

    /** Immutable, request-scoped policy output containing only bounded values. */
    public record Decision(
            FeatureMode featureMode,
            ResponseStyle responseStyle,
            SecurityStance securityStance,
            EvidenceMode evidenceMode,
            MemoryWriteMode memoryWriteMode,
            FailureMode failureMode,
            List<ManipulationFact> confirmedManipulationFacts) {

        public Decision {
            featureMode = featureMode == null ? FeatureMode.OFF : featureMode;
            responseStyle = responseStyle == null ? ResponseStyle.STANDARD : responseStyle;
            securityStance = securityStance == null ? SecurityStance.NEUTRAL : securityStance;
            evidenceMode = evidenceMode == null ? EvidenceMode.BASELINE : evidenceMode;
            memoryWriteMode = memoryWriteMode == null ? MemoryWriteMode.NORMAL : memoryWriteMode;
            failureMode = failureMode == null ? FailureMode.LEGACY_FAIL_SOFT : failureMode;
            confirmedManipulationFacts = confirmedManipulationFacts == null
                    ? List.of()
                    : confirmedManipulationFacts.stream()
                            .filter(Objects::nonNull)
                            .distinct()
                            .sorted(FACT_ORDER)
                            .toList();
        }

        public boolean enforcementActive() {
            return featureMode == FeatureMode.ENFORCE;
        }

        public boolean shouldTrace() {
            return featureMode != FeatureMode.OFF;
        }

        public boolean defensive() {
            return enforcementActive() && securityStance == SecurityStance.DEFENSIVE;
        }

        public boolean strictEvidence() {
            return defensive() && evidenceMode == EvidenceMode.STRICT;
        }

        public boolean suppressMemoryWrites() {
            return enforcementActive() && memoryWriteMode == MemoryWriteMode.SUPPRESS;
        }

        public boolean failClosed() {
            return enforcementActive() && failureMode == FailureMode.FAIL_CLOSED;
        }

        public int signalCount() {
            return confirmedManipulationFacts.size();
        }

        public Set<ManipulationKind> manipulationKinds() {
            return enumSet(ManipulationKind.class,
                    confirmedManipulationFacts.stream().map(ManipulationFact::kind).toList());
        }

        public Set<ProofKind> proofKinds() {
            return enumSet(ProofKind.class,
                    confirmedManipulationFacts.stream().map(ManipulationFact::proofKind).toList());
        }

        public Set<DetectorRule> detectorRules() {
            return enumSet(DetectorRule.class,
                    confirmedManipulationFacts.stream().map(ManipulationFact::detectorRule).toList());
        }

        public Set<SourceSurface> sourceSurfaces() {
            return enumSet(SourceSurface.class,
                    confirmedManipulationFacts.stream().map(ManipulationFact::sourceSurface).toList());
        }

        public VisionMode enforceVision(VisionMode requested) {
            if (strictEvidence()) {
                return VisionMode.STRICT;
            }
            return requested == null ? VisionMode.HYBRID : requested;
        }

        public AnswerMode enforceAnswerMode(AnswerMode requested) {
            if (strictEvidence()) {
                return AnswerMode.FACT;
            }
            return requested == null ? AnswerMode.ALL_ROUNDER : requested;
        }

        public GuardProfile enforceGuardProfile(GuardProfile requested) {
            if (strictEvidence()) {
                return GuardProfile.STRICT;
            }
            return requested == null ? GuardProfile.NORMAL : requested;
        }
    }

    private static <E extends Enum<E>> Set<E> enumSet(Class<E> type, List<E> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        EnumSet<E> out = EnumSet.noneOf(type);
        out.addAll(values);
        return Collections.unmodifiableSet(out);
    }

    private static String normalize(String request) {
        String safe = request == null ? "" : request;
        String normalized = Normalizer.normalize(safe, Normalizer.Form.NFKC);
        normalized = FORMAT_CHARACTERS.matcher(normalized).replaceAll("");
        return WHITESPACE.matcher(normalized.trim()).replaceAll(" ");
    }
}
