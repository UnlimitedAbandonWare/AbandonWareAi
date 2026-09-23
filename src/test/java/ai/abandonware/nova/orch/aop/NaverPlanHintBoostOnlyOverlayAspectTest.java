package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NaverPlanHintBoostOnlyOverlayProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NaverPlanHintBoostOnlyOverlayAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
        MDC.clear();
    }

    @Test
    void planHintTraceStoresQueryHashOnly() throws Throwable {
        String rawQuery = "private plan hint query token=naver-planhint-secret";
        String rawRid = "rid-private-owner-token-123";
        String rawSid = "sid-private-owner-token-456";
        MDC.put("x-request-id", rawRid);
        MDC.put("x-session-id", rawSid);
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        GuardContextHolder.set(context);

        NaverPlanHintBoostOnlyOverlayAspect aspect =
                new NaverPlanHintBoostOnlyOverlayAspect(new NaverPlanHintBoostOnlyOverlayProperties());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("searchWithTraceSync");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{rawQuery});
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.aroundNaverSync(pjp);

        assertEquals("ok", result);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(rawRid), trace);
        assertFalse(trace.contains(rawSid), trace);
        assertFalse(trace.contains("naver-planhint-secret"), trace);
        assertNull(TraceStore.get("web.naver.planHintBoostOnly.query"));
        assertTrue(String.valueOf(TraceStore.get("web.naver.planHintBoostOnly.queryHash")).startsWith("hash:"));
        assertEquals(rawQuery.length(), TraceStore.get("web.naver.planHintBoostOnly.queryLength"));
        assertEquals(SafeRedactor.hashValue(rawRid), TraceStore.get("web.naver.planHintBoostOnly.rid"));
        assertEquals(SafeRedactor.hashValue(rawSid), TraceStore.get("web.naver.planHintBoostOnly.sessionId"));
    }

    @Test
    void weakPromotedLocationTokenStoresHashOnly() throws Throwable {
        String rawSuffix = "\uAD6C";
        String rawAllowToken = "\uC911\uAD6C";
        String rawToken = "\uAC15\uB0A8" + rawSuffix;
        String rawQuery = rawToken + " map";
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        GuardContextHolder.set(context);

        NaverPlanHintBoostOnlyOverlayProperties props = new NaverPlanHintBoostOnlyOverlayProperties();
        props.getLocation().setWeakOnlyPromoteEnabled(true);
        props.getLocation().setKeywords(List.of("map"));
        props.getLocation().setWeakKeywords(List.of("map"));
        props.getLocation().setWeakOnlyPromoteSuffixes(List.of(rawSuffix));
        props.getLocation().setWeakOnlyPromoteMinPrefixChars(1);
        props.getLocation().setWeakOnlyPromoteMinPrefixCharsBySuffix(Map.of(rawSuffix, 1));
        props.getLocation().setWeakOnlyPromoteDenyKeywords(List.of());
        props.getLocation().setWeakOnlyPromoteAllowTokens(List.of(rawAllowToken));

        NaverPlanHintBoostOnlyOverlayAspect aspect = new NaverPlanHintBoostOnlyOverlayAspect(props);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("searchWithTraceSync");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{rawQuery});
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.aroundNaverSync(pjp);

        assertEquals("ok", result);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawToken), trace);
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(rawSuffix), trace);
        assertFalse(trace.contains(rawAllowToken), trace);
        assertEquals(SafeRedactor.hashValue(rawToken),
                TraceStore.get("web.naver.planHintBoostOnly.location.weakPromoted.token"));
        assertEquals(SafeRedactor.hashValue(rawSuffix),
                TraceStore.get("web.naver.planHintBoostOnly.location.weakPromoted.suffix"));
        assertEquals(rawToken.length(),
                TraceStore.get("web.naver.planHintBoostOnly.location.weakPromoted.tokenLength"));
    }

    @Test
    void appliedConfigSnapshotStoresLocationTuningHashesOnly() throws Throwable {
        String rawSuffix = "\uAD6C";
        String rawAllowToken = "\uC911\uAD6C";
        String rawQuery = "plain plan hint query";
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        GuardContextHolder.set(context);

        NaverPlanHintBoostOnlyOverlayProperties props = new NaverPlanHintBoostOnlyOverlayProperties();
        props.getLocation().setWeakOnlyPromoteEnabled(true);
        props.getLocation().setKeywords(List.of("map"));
        props.getLocation().setWeakKeywords(List.of("map"));
        props.getLocation().setWeakOnlyPromoteSuffixes(List.of(rawSuffix));
        props.getLocation().setWeakOnlyPromoteMinPrefixCharsBySuffix(Map.of(rawSuffix, 1));
        props.getLocation().setWeakOnlyPromoteAllowTokens(List.of(rawAllowToken));

        NaverPlanHintBoostOnlyOverlayAspect aspect = new NaverPlanHintBoostOnlyOverlayAspect(props);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("searchWithTraceSync");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{rawQuery});
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.aroundNaverSync(pjp);

        assertEquals("ok", result);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawSuffix), trace);
        assertFalse(trace.contains(rawAllowToken), trace);
        assertEquals(List.of(SafeRedactor.hashValue(rawSuffix)),
                TraceStore.get("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.suffixes"));
        assertEquals(Map.of(SafeRedactor.hashValue(rawSuffix), 1),
                TraceStore.get("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.minPrefixBySuffix"));
        assertEquals(List.of(SafeRedactor.hashValue(rawAllowToken)),
                TraceStore.get("web.naver.planHintBoostOnly.config.location.weakOnlyPromote.allowTokens"));
    }

    @Test
    void weakPromoteDeniedKeywordStoresHashOnly() throws Throwable {
        String rawDenyKeyword = "privatedeny";
        String rawQuery = rawDenyKeyword + " map";
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        GuardContextHolder.set(context);

        NaverPlanHintBoostOnlyOverlayProperties props = new NaverPlanHintBoostOnlyOverlayProperties();
        props.getLocation().setWeakOnlyPromoteEnabled(true);
        props.getLocation().setKeywords(List.of("map"));
        props.getLocation().setWeakKeywords(List.of("map"));
        props.getLocation().setWeakOnlyPromoteDenyKeywords(List.of(rawDenyKeyword));
        props.getLocation().setWeakOnlyPromoteSuffixes(List.of());
        props.getLocation().setWeakOnlyPromoteAllowTokens(List.of());

        NaverPlanHintBoostOnlyOverlayAspect aspect = new NaverPlanHintBoostOnlyOverlayAspect(props);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("searchWithTraceSync");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{rawQuery});
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.aroundNaverSync(pjp);

        assertEquals("ok", result);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawDenyKeyword), trace);
        assertFalse(trace.contains(rawQuery), trace);
        assertEquals(SafeRedactor.hashValue(rawDenyKeyword),
                TraceStore.get("web.naver.planHintBoostOnly.location.weakPromoteDenied.keyword"));
    }

    @Test
    void localIntentKeywordStoresHashOnly() throws Throwable {
        String rawLocalIntentKeyword = "privatelocal";
        String rawQuery = rawLocalIntentKeyword + " schedule";
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        GuardContextHolder.set(context);

        NaverPlanHintBoostOnlyOverlayProperties props = new NaverPlanHintBoostOnlyOverlayProperties();
        props.getLocation().setLocalIntentEnabled(true);
        props.getLocation().setLocalIntentKeywords(List.of(rawLocalIntentKeyword));

        NaverPlanHintBoostOnlyOverlayAspect aspect = new NaverPlanHintBoostOnlyOverlayAspect(props);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("searchWithTraceSync");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{rawQuery});
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.aroundNaverSync(pjp);

        assertEquals("ok", result);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawLocalIntentKeyword), trace);
        assertFalse(trace.contains(rawQuery), trace);
        assertEquals(SafeRedactor.hashValue(rawLocalIntentKeyword),
                TraceStore.get("web.naver.planHintBoostOnly.location.localIntentKeyword"));
    }

    @Test
    void negativeKeywordStoresHashOnly() throws Throwable {
        String rawNegativeKeyword = "privatenegative";
        String rawQuery = rawNegativeKeyword + " map";
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        GuardContextHolder.set(context);

        NaverPlanHintBoostOnlyOverlayProperties props = new NaverPlanHintBoostOnlyOverlayProperties();
        props.getLocation().setKeywords(List.of("map"));
        props.getLocation().setWeakKeywords(List.of("map"));
        props.getLocation().setNegativeKeywords(List.of(rawNegativeKeyword));
        props.getLocation().setNegativeOnlyAffectsWeakKeywords(false);

        NaverPlanHintBoostOnlyOverlayAspect aspect = new NaverPlanHintBoostOnlyOverlayAspect(props);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("searchWithTraceSync");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{rawQuery});
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.aroundNaverSync(pjp);

        assertEquals("ok", result);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawNegativeKeyword), trace);
        assertFalse(trace.contains(rawQuery), trace);
        assertEquals(SafeRedactor.hashValue(rawNegativeKeyword),
                TraceStore.get("web.naver.planHintBoostOnly.location.negativeKeyword"));
    }

    @Test
    void locationHitKeywordListsStoreHashesOnly() throws Throwable {
        String rawWeakKeyword = "privateweak";
        String rawQuery = rawWeakKeyword + " nearby";
        GuardContext context = new GuardContext();
        context.setOfficialOnly(true);
        GuardContextHolder.set(context);

        NaverPlanHintBoostOnlyOverlayProperties props = new NaverPlanHintBoostOnlyOverlayProperties();
        props.getLocation().setKeywords(List.of(rawWeakKeyword));
        props.getLocation().setWeakKeywords(List.of(rawWeakKeyword));
        props.getLocation().setWeakOnlyPromoteEnabled(false);

        NaverPlanHintBoostOnlyOverlayAspect aspect = new NaverPlanHintBoostOnlyOverlayAspect(props);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("searchWithTraceSync");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{rawQuery});
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.aroundNaverSync(pjp);

        assertEquals("ok", result);
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawWeakKeyword), trace);
        assertFalse(trace.contains(rawQuery), trace);
        assertEquals(List.of(SafeRedactor.hashValue(rawWeakKeyword)),
                TraceStore.get("web.naver.planHintBoostOnly.location.hitWeakKeywords"));
    }

    @Test
    void uppercaseLocationKeywordIsMatchedIndependentOfDefaultLocale() throws Throwable {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            GuardContext context = new GuardContext();
            context.setOfficialOnly(true);
            GuardContextHolder.set(context);

            NaverPlanHintBoostOnlyOverlayProperties props = new NaverPlanHintBoostOnlyOverlayProperties();
            props.getLocation().setLocalIntentEnabled(false);
            props.getLocation().setKeywords(List.of("location"));
            props.getLocation().setWeakKeywords(List.of());
            props.getLocation().setNegativeKeywords(List.of());

            NaverPlanHintBoostOnlyOverlayAspect aspect = new NaverPlanHintBoostOnlyOverlayAspect(props);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            Signature signature = mock(Signature.class);
            when(signature.getName()).thenReturn("searchWithTraceSync");
            when(pjp.getSignature()).thenReturn(signature);
            when(pjp.getArgs()).thenReturn(new Object[]{"LOCATION nearby"});
            when(pjp.proceed()).thenReturn("ok");

            assertEquals("ok", aspect.aroundNaverSync(pjp));
            assertEquals("skipped:location", TraceStore.get("web.naver.planHintBoostOnly.decision"));
            assertEquals(Boolean.TRUE, TraceStore.get("web.naver.planHintBoostOnly.location.strongHit"));
            assertEquals(Boolean.FALSE, TraceStore.get("web.naver.planHintBoostOnly.applied"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void matchingAndNonmatchingDecisionsAgreeAcrossDefaultLocales() throws Throwable {
        String usMatch = locationDecisionUnder(Locale.US, "LOCATION nearby");
        String turkishMatch = locationDecisionUnder(Locale.forLanguageTag("tr-TR"), "LOCATION nearby");
        String usNonmatch = locationDecisionUnder(Locale.US, "ordinary question");
        String turkishNonmatch = locationDecisionUnder(Locale.forLanguageTag("tr-TR"), "ordinary question");

        assertEquals("skipped:location", usMatch);
        assertEquals(usMatch, turkishMatch);
        assertEquals("applied", usNonmatch);
        assertEquals(usNonmatch, turkishNonmatch);
    }

    private static String locationDecisionUnder(Locale locale, String query) throws Throwable {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(locale);
            TraceStore.clear();
            GuardContextHolder.clear();

            GuardContext context = new GuardContext();
            context.setOfficialOnly(true);
            GuardContextHolder.set(context);
            NaverPlanHintBoostOnlyOverlayProperties props = new NaverPlanHintBoostOnlyOverlayProperties();
            props.getLocation().setLocalIntentEnabled(false);
            props.getLocation().setKeywords(List.of("location"));
            props.getLocation().setWeakKeywords(List.of());
            props.getLocation().setNegativeKeywords(List.of());

            NaverPlanHintBoostOnlyOverlayAspect aspect = new NaverPlanHintBoostOnlyOverlayAspect(props);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            Signature signature = mock(Signature.class);
            when(signature.getName()).thenReturn("searchWithTraceSync");
            when(pjp.getSignature()).thenReturn(signature);
            when(pjp.getArgs()).thenReturn(new Object[]{query});
            when(pjp.proceed()).thenReturn("ok");

            aspect.aroundNaverSync(pjp);
            return String.valueOf(TraceStore.get("web.naver.planHintBoostOnly.decision"));
        } finally {
            GuardContextHolder.clear();
            TraceStore.clear();
            Locale.setDefault(previous);
        }
    }

    @Test
    void planHintBoostOnlyTraceCatchesUseSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/NaverPlanHintBoostOnlyOverlayAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.guardContext\", t);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.planHintStrictTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.noopTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.skippedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.appliedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.restoreTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.localIntentTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.negativeTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.locationTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.weakPromotedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.weakOnlyIgnoredTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.weakPromoteDeniedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.allowTokenTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.suffixPromoteTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"naverPlanHintBoostOnly.planOverridesCopy\", ignore);"));
    }
}
