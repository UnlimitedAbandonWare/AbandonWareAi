package com.example.lms.moe;

import com.example.lms.artplate.ArtPlateEvolver;
import com.example.lms.artplate.ArtPlateRegistry;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RgbStrategySelectorTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void selectPublishesOperationalDecisionTrace() {
        RgbStrategySelector selector = new RgbStrategySelector();
        RgbLogSignalParser.Features features = new RgbLogSignalParser.Features(
                Map.of(RgbLogSignalParser.SIG_LOW_EVIDENCE, 1),
                List.of());
        RgbResourceProbe.Snapshot snapshot = new RgbResourceProbe.Snapshot(
                true, true, true, List.of(), "red", "green", 0L);

        RgbStrategySelector.Decision decision = selector.select(features, snapshot);

        assertEquals(decision.primaryStrategy().name(), TraceStore.get("moe.strategy.primary"));
        assertEquals(decision.fallbackStrategies().size(), TraceStore.get("moe.strategy.fallbackCount"));
        assertEquals(decision.scoreCard().redScore(), TraceStore.get("moe.strategy.redScore"));
        assertEquals(decision.scoreCard().greenScore(), TraceStore.get("moe.strategy.greenScore"));
        assertEquals(decision.scoreCard().blueScore(), TraceStore.get("moe.strategy.blueScore"));
        assertEquals(decision.reasons().get(0).tag(), TraceStore.get("moe.strategy.topReason"));
    }

    @Test
    void selectPublishesArtPlateEvolverSuggestionWhenAvailable() throws Exception {
        Constructor<RgbStrategySelector> constructor = RgbStrategySelector.class
                .getDeclaredConstructor(ArtPlateEvolver.class, ArtPlateRegistry.class);
        constructor.setAccessible(true);
        RgbStrategySelector selector = constructor.newInstance(new ArtPlateEvolver(), new ArtPlateRegistry());
        RgbLogSignalParser.Features features = new RgbLogSignalParser.Features(
                Map.of(RgbLogSignalParser.SIG_LOW_EVIDENCE, 1),
                List.of());
        RgbResourceProbe.Snapshot snapshot = new RgbResourceProbe.Snapshot(
                true, true, true, List.of(), "red", "green", 0L);

        selector.select(features, snapshot);

        assertEquals(Boolean.TRUE, TraceStore.get("moe.evolverPlateRegistered"));
        assertEquals(Boolean.TRUE, TraceStore.get("moe.artplate.evolver.available"));
        assertEquals("AP3_VEC_DENSE", TraceStore.get("moe.artplate.base"));
        assertTrue(String.valueOf(TraceStore.get("moe.artplate.evolver.suggested")).startsWith("AP3_VEC_DENSE_M"));
    }

    @Test
    void unavailableArtPlateEvolverClearsPreviousSuggestionBreadcrumbs() throws Exception {
        Constructor<RgbStrategySelector> constructor = RgbStrategySelector.class
                .getDeclaredConstructor(ArtPlateEvolver.class, ArtPlateRegistry.class);
        constructor.setAccessible(true);
        RgbStrategySelector withEvolver = constructor.newInstance(new ArtPlateEvolver(), new ArtPlateRegistry());
        RgbLogSignalParser.Features features = new RgbLogSignalParser.Features(
                Map.of(RgbLogSignalParser.SIG_LOW_EVIDENCE, 1),
                List.of());
        RgbResourceProbe.Snapshot snapshot = new RgbResourceProbe.Snapshot(
                true, true, true, List.of(), "red", "green", 0L);

        withEvolver.select(features, snapshot);
        assertEquals("AP3_VEC_DENSE", TraceStore.get("moe.artplate.base"));

        new RgbStrategySelector().select(features, snapshot);

        assertEquals(Boolean.FALSE, TraceStore.get("moe.evolverPlateRegistered"));
        assertEquals(Boolean.FALSE, TraceStore.get("moe.artplate.evolver.available"));
        assertEquals("evolver_unavailable", TraceStore.get("moe.artplate.base"));
        assertEquals("evolver_unavailable", TraceStore.get("moe.artplate.evolver.bucket"));
        assertEquals("evolver_unavailable", TraceStore.get("moe.artplate.evolver.suggested"));
    }

    @Test
    void fullyHealthyExpertsCanSelectRgbEnsembleAsCooperativePrimary() {
        RgbStrategySelector selector = new RgbStrategySelector();
        RgbResourceProbe.Snapshot snapshot = new RgbResourceProbe.Snapshot(
                true, true, true, List.of(), "red", "green", 0L);

        RgbStrategySelector.Decision decision = selector.select(RgbLogSignalParser.Features.empty(), snapshot);

        assertEquals(RgbStrategySelector.Strategy.RGB_ENSEMBLE, decision.primaryStrategy());
        Map<String, Integer> scores = decision.scoreCard().strategyScores();
        assertTrue(scores.get("RGB_ENSEMBLE") > scores.get("RG_ENSEMBLE"));
        assertEquals("RGB_ENSEMBLE", TraceStore.get("moe.strategy.primary"));
    }

    @Test
    void fullyDegradedExpertsKeepRgbEnsembleScoreNegativeAndUnusable() {
        RgbStrategySelector selector = new RgbStrategySelector();
        RgbResourceProbe.Snapshot snapshot = new RgbResourceProbe.Snapshot(
                false, false, false, List.of(), null, null, 0L);

        RgbStrategySelector.Decision decision = selector.select(RgbLogSignalParser.Features.empty(), snapshot);

        Map<String, Integer> scores = decision.scoreCard().strategyScores();
        assertTrue(scores.get("RGB_ENSEMBLE") < 0);
        RgbStrategySelector.Candidate rgb = decision.scoreCard().rankedCandidates().stream()
                .filter(candidate -> candidate.strategy() == RgbStrategySelector.Strategy.RGB_ENSEMBLE)
                .findFirst()
                .orElseThrow();
        assertEquals(false, rgb.usable());
    }

    @Test
    void strategyEnumExposesNinePlateAlias() throws Exception {
        Method alias = RgbStrategySelector.Strategy.class.getDeclaredMethod("artPlateAlias");

        assertEquals("AP3_VEC_DENSE", alias.invoke(RgbStrategySelector.Strategy.R_ONLY));
        assertEquals("AP9_COST_SAVER", alias.invoke(RgbStrategySelector.Strategy.G_ONLY));
        assertEquals("AP1_AUTH_WEB", alias.invoke(RgbStrategySelector.Strategy.B_ONLY));
    }

    @Test
    void invalidStrategyNameFallbackLeavesScannerVisibleBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/moe/RgbStrategySelector.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"moe.strategy.suppressed.invalidStrategyName\", true)"));
        assertTrue(source.contains("TraceStore.put(\"moe.strategy.suppressed.stage\", \"invalidStrategyName\")"));
        assertTrue(source.contains("TraceStore.put(\"moe.strategy.suppressed.errorType\", errorType)"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(source.contains("[AWX][moe.rgb] invalid strategy suppressed"));
        assertTrue(source.contains("log.debug"));
    }

    @Test
    void invalidStrategyNameFallbackLeavesAggregateBreadcrumbWithoutRawValue() throws Exception {
        Method buildRankedCandidates = RgbStrategySelector.class.getDeclaredMethod(
                "buildRankedCandidates", Map.class, List.class, RgbResourceProbe.Snapshot.class, int.class);
        buildRankedCandidates.setAccessible(true);
        RgbResourceProbe.Snapshot snapshot = new RgbResourceProbe.Snapshot(
                true, true, true, List.of(), "red", "green", 0L);

        buildRankedCandidates.invoke(null,
                Map.of("ownerToken=secret-strategy", 1),
                List.of(),
                snapshot,
                1);

        assertEquals(Boolean.TRUE, TraceStore.get("moe.strategy.suppressed.invalidStrategyName"));
        assertEquals("IllegalArgumentException", TraceStore.get("moe.strategy.suppressed.invalidStrategyName.errorType"));
        assertEquals("invalidStrategyName", TraceStore.get("moe.strategy.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("moe.strategy.suppressed.errorType"));
        assertTrue(!String.valueOf(TraceStore.getAll()).contains("ownerToken=secret-strategy"));
    }
}
