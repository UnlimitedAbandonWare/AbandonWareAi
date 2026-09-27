package com.example.lms.infra.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ReplaySelectionEntropyTest {

    private static final SelectionCoordinate GOLDEN = new SelectionCoordinate(
            "ensemble.profile.temperature", "node:support", 0L, 0L);

    @Test
    void freezesSelectionEntropyV1GoldenVector() {
        ReplaySelectionEntropy entropy = replay();

        assertThat(entropy.seedFingerprint()).isEqualTo("009e8892e5b3");
        assertThat(entropy.unitInterval(GOLDEN)).isEqualTo(0.5613173776353443d);
        assertThat(entropy.boundedIndex(GOLDEN, 97)).isEqualTo(23);
    }

    @Test
    void evaluationAndSubmissionOrderDoNotChangeCoordinateValues() throws Exception {
        List<SelectionCoordinate> abc = List.of(
                new SelectionCoordinate("ensemble.profile.temperature", "node:support", 0, 0),
                new SelectionCoordinate("ensemble.profile.top-p", "node:support", 0, 0),
                new SelectionCoordinate("ensemble.profile.shuffle", "node:falsify", 0, 3));
        Map<SelectionCoordinate, Double> forward = values(abc);
        List<SelectionCoordinate> reversed = new ArrayList<>(abc);
        Collections.reverse(reversed);
        Map<SelectionCoordinate, Double> reverse = values(reversed);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Map.Entry<SelectionCoordinate, Double>>> futures = new ArrayList<>();
            for (SelectionCoordinate coordinate : reversed) {
                futures.add(pool.submit(() -> Map.entry(coordinate, replay().unitInterval(coordinate))));
            }
            Map<SelectionCoordinate, Double> parallel = new HashMap<>();
            for (Future<Map.Entry<SelectionCoordinate, Double>> future : futures) {
                Map.Entry<SelectionCoordinate, Double> value = future.get(5, TimeUnit.SECONDS);
                parallel.put(value.getKey(), value.getValue());
            }
            assertThat(reverse).isEqualTo(forward);
            assertThat(parallel).isEqualTo(forward);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidSeedBoundAndFourBiasedBlocksWithoutLiveFallback() {
        assertThatThrownBy(() -> SelectionReplaySpec.v1(new byte[15]))
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.REPLAY_INVALID);
        assertThatThrownBy(() -> SelectionReplaySpec.v1(new byte[65]))
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.REPLAY_INVALID);
        assertThatThrownBy(() -> replay().boundedIndex(GOLDEN, 0))
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.DERIVATION_INVALID);
        assertThatThrownBy(() -> replay().boundedIndex(GOLDEN, 1_000_001))
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.DERIVATION_INVALID);
        ReplaySelectionEntropy rejecting = new ReplaySelectionEntropy(
                seedDeckZero(), (coordinate, block) -> {
                    byte[] bytes = new byte[32];
                    Arrays.fill(bytes, (byte) 0xff);
                    return bytes;
                });
        assertThatThrownBy(() -> rejecting.boundedIndex(GOLDEN, 1_000_000))
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.DERIVATION_INVALID);
        assertThat(replay().boundedIndex(GOLDEN, 1)).isZero();
    }

    @Test
    void rejectsMalformedDerivedBlocksWithTheFixedReason() {
        ReplaySelectionEntropy nullDigest = new ReplaySelectionEntropy(
                seedDeckZero(), (coordinate, block) -> null);
        ReplaySelectionEntropy shortDigest = new ReplaySelectionEntropy(
                seedDeckZero(), (coordinate, block) -> new byte[31]);

        assertThatThrownBy(() -> nullDigest.unitInterval(GOLDEN))
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.DERIVATION_INVALID);
        assertThatThrownBy(() -> shortDigest.boundedIndex(GOLDEN, 7))
                .isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.DERIVATION_INVALID);
    }

    @Test
    void factoryInitializationFailureUsesTheFixedReason() {
        SelectionReplaySpec spec = SelectionReplaySpec.v1(seedDeckZero());
        assertThatThrownBy(() -> SelectionEntropyFactory.replay(spec, () -> {
            throw new GeneralSecurityException("fixed-test-init-failure");
        })).isInstanceOf(SelectionEntropyException.class)
                .extracting(error -> ((SelectionEntropyException) error).reason())
                .isEqualTo(SelectionEntropyReason.REPLAY_INIT_FAILED);
    }

    @Test
    void decisionActorAttemptAndDrawFieldsAreDomainSeparated() {
        ReplaySelectionEntropy entropy = replay();
        Set<Double> values = Stream.of(
                new SelectionCoordinate("ensemble.profile.temperature", "node:support", 0, 0),
                new SelectionCoordinate("ensemble.profile.top-p", "node:support", 0, 0),
                new SelectionCoordinate("ensemble.profile.temperature", "node:falsify", 0, 0),
                new SelectionCoordinate("ensemble.profile.temperature", "node:support", 1, 0),
                new SelectionCoordinate("ensemble.profile.temperature", "node:support", 0, 1))
                .map(entropy::unitInterval)
                .collect(Collectors.toSet());
        assertThat(values).hasSize(5);
    }

    @Test
    void publicSurfaceAndStringsNeverExposeSeedBytes() throws Exception {
        byte[] seed = seedDeckZero();
        SelectionReplaySpec spec = SelectionReplaySpec.v1(seed);
        Arrays.fill(seed, (byte) 0x7f);
        ReplaySelectionEntropy entropy = (ReplaySelectionEntropy) SelectionEntropyFactory.replay(spec);

        assertThat(spec.toString()).doesNotContain("00010203");
        assertThat(Arrays.stream(Introspector.getBeanInfo(SelectionReplaySpec.class)
                        .getPropertyDescriptors())
                .map(PropertyDescriptor::getName))
                .containsExactly("class");
        assertThat(Arrays.stream(SelectionReplaySpec.class.getMethods())
                .noneMatch(method -> method.getReturnType().equals(byte[].class))).isTrue();
        assertThat(entropy.seedFingerprint()).isEqualTo("009e8892e5b3");
        assertThat(entropy.unitInterval(GOLDEN)).isEqualTo(0.5613173776353443d);
    }

    private static ReplaySelectionEntropy replay() {
        return (ReplaySelectionEntropy) SelectionEntropyFactory.replay(
                SelectionReplaySpec.v1(seedDeckZero()));
    }

    private static Map<SelectionCoordinate, Double> values(List<SelectionCoordinate> coordinates) {
        Map<SelectionCoordinate, Double> result = new HashMap<>();
        ReplaySelectionEntropy entropy = replay();
        for (SelectionCoordinate coordinate : coordinates) {
            result.put(coordinate, entropy.unitInterval(coordinate));
        }
        return result;
    }

    private static byte[] seedDeckZero() {
        byte[] seed = new byte[32];
        for (int index = 0; index < seed.length; index++) {
            seed[index] = (byte) index;
        }
        return seed;
    }
}
