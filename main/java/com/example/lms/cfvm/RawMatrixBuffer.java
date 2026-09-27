
package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.learn.CfvmKallocLearningProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;



/**
 * Small in-memory buffer for compact failure-pattern tuples.
 * It is session/process local telemetry, not durable storage or a vector index.
 *
 * <p>Boundary note: this buffer is not currently wired directly to FailurePatternOrchestrator;
 * current failure-pattern cooldown policy remains threshold-based until an explicit CFVM integration is added.
 *
 * <p>Current implementation scope (2026-06): stores compact failure-pattern tuples
 * in a capacity-9 ring buffer, plus a local Boltzmann softmax snapshot for
 * {@code CFVM_BOLTZMANN_LEARNING}. The weight API records
 * {@code BOLTZMANN_LEARNING_ACTIVE}, but remains process-local telemetry;
 * durable learner integration should still flow through {@link CfvmFailureRecorder}.
 */
@Component
public class RawMatrixBuffer {
    private static final int DEFAULT_CAPACITY = 9;
    private static final double DEFAULT_BOLTZMANN_TEMP = 0.35d;
    private static final String DEFAULT_TEMP_SOURCE = "CfvmKallocLearningProperties";
    private static final String MANUAL_TEMP_SOURCE = "manual_override";
    private static final String RUNTIME_SNAPSHOT_TEMP_SOURCE = "runtime_buffer_snapshot";

    private final int capacity;
    private final ArrayDeque<Entry> q;
    private final double[] rawScores;
    private final double[] weights;
    private volatile double boltzmannTemp;

    public RawMatrixBuffer() {
        this(DEFAULT_CAPACITY, DEFAULT_BOLTZMANN_TEMP);
    }

    @Autowired
    public RawMatrixBuffer(CfvmKallocLearningProperties props) {
        this(DEFAULT_CAPACITY, props == null ? DEFAULT_BOLTZMANN_TEMP : props.getBoltzmannTemperature());
    }

    RawMatrixBuffer(int capacity) {
        this(capacity, DEFAULT_BOLTZMANN_TEMP);
    }

    RawMatrixBuffer(int capacity, double boltzmannTemp) {
        this.capacity = Math.max(1, capacity);
        this.q = new ArrayDeque<>(this.capacity);
        this.rawScores = new double[this.capacity];
        this.weights = new double[this.capacity];
        this.boltzmannTemp = normalizeBoltzmannTemp(boltzmannTemp);
    }

    public synchronized void add(long id, long a, long b) {
        offer(new Entry(id, a, b));
    }

    public synchronized void offer(Entry entry) {
        if (entry == null) {
            return;
        }
        while (q.size() >= capacity) {
            q.removeFirst();
        }
        q.addLast(entry);
    }

    public synchronized List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(q));
    }

    public synchronized int size() {
        return q.size();
    }

    public synchronized void updateWeight(int slotIndex, double weight) {
        if (slotIndex < 0 || slotIndex >= capacity) {
            return;
        }
        rawScores[slotIndex] = Double.isFinite(weight) ? weight : 0.0d;
        rebalanceBoltzmann();
        TraceStore.put("cfvm.rawBuffer.weightMode", "BOLTZMANN_LEARNING_ACTIVE");
        TraceStore.put("cfvm.rawBuffer.weightUpdated", true);
        TraceStore.put("cfvm.rawBuffer.weightUpdated.slot", slotIndex);
        TraceStore.put("cfvm.rawBuffer.boltzmannTemp", boltzmannTemp);
        TraceStore.put("cfvm.boltzmannTemp", boltzmannTemp);
        TraceStore.put("cfvm.tempSource", DEFAULT_TEMP_SOURCE);
        TraceStore.put("cfvm.tempAnnealApplied", false);
    }

    public synchronized void setBoltzmannTemp(double temp) {
        boltzmannTemp = normalizeBoltzmannTemp(temp);
        rebalanceBoltzmann();
        TraceStore.put("cfvm.rawBuffer.boltzmannTemp", boltzmannTemp);
        TraceStore.put("cfvm.boltzmannTemp", boltzmannTemp);
        TraceStore.put("cfvm.tempSource", MANUAL_TEMP_SOURCE);
        TraceStore.put("cfvm.tempAnnealApplied", true);
    }

    private void rebalanceBoltzmann() {
        double temp = normalizeBoltzmannTemp(boltzmannTemp);
        double max = rawScores[0];
        for (int i = 1; i < capacity; i++) {
            max = Math.max(max, rawScores[i]);
        }
        double sum = 0.0d;
        double[] exps = new double[capacity];
        for (int i = 0; i < capacity; i++) {
            exps[i] = Math.exp((rawScores[i] - max) / temp);
            sum += exps[i];
        }
        if (!Double.isFinite(sum) || sum <= 0.0d) {
            TraceStore.put("cfvm.rawBuffer.boltzmannSum.degenerate", true);
            TraceStore.put("cfvm.rawBuffer.boltzmannSum.value", Double.isFinite(sum) ? sum : "non_finite");
            TraceStore.put("cfvm.rawBuffer.boltzmannRebalanced", false);
            return;
        }
        for (int i = 0; i < capacity; i++) {
            weights[i] = exps[i] / sum;
        }
        TraceStore.put("cfvm.rawBuffer.boltzmannSum.degenerate", false);
        TraceStore.put("cfvm.rawBuffer.boltzmannSum", sum);
        TraceStore.put("cfvm.rawBuffer.boltzmannRebalanced", true);
    }

    private static double normalizeBoltzmannTemp(double temp) {
        return Double.isFinite(temp) && temp > 0.0d ? temp : DEFAULT_BOLTZMANN_TEMP;
    }

    public synchronized double[] getWeights() {
        return Arrays.copyOf(weights, capacity);
    }

    public synchronized double[] exportWeights() {
        return getWeights();
    }

    public synchronized double getBoltzmannTemp() {
        return boltzmannTemp;
    }

    public synchronized void publishHarmonyTrace() {
        TraceStore.put("cfvm.rawBuffer.boltzmannTemp", boltzmannTemp);
        TraceStore.put("cfvm.boltzmannTemp", boltzmannTemp);
        TraceStore.put("cfvm.tempSource", RUNTIME_SNAPSHOT_TEMP_SOURCE);
        TraceStore.put("cfvm.tempAnnealApplied", true);
    }

    public synchronized void restoreFromSnapshot(double[] savedWeights, double savedTemp) {
        if (savedWeights == null || savedWeights.length != capacity) {
            TraceStore.put("cfvm.rawBuffer.restoreSkipped", "weight_count_mismatch");
            return;
        }
        double[] candidateWeights = Arrays.copyOf(savedWeights, capacity);
        if (!Double.isFinite(savedTemp) || savedTemp <= 0.0d) {
            TraceStore.put("cfvm.rawBuffer.restoreSkipped", "invalid_temperature");
            throw new IllegalArgumentException("snapshot temperature must be finite and positive");
        }
        for (double savedWeight : candidateWeights) {
            if (!Double.isFinite(savedWeight) || savedWeight < 0.0d) {
                TraceStore.put("cfvm.rawBuffer.restoreSkipped", "invalid_weight");
                throw new IllegalArgumentException("snapshot weights must be finite and nonnegative");
            }
        }
        System.arraycopy(candidateWeights, 0, weights, 0, capacity);
        System.arraycopy(candidateWeights, 0, rawScores, 0, capacity);
        boltzmannTemp = savedTemp;
        TraceStore.put("cfvm.rawBuffer.restoredFromSnapshot", true);
        TraceStore.put("cfvm.rawBuffer.boltzmannTemp", boltzmannTemp);
        TraceStore.put("cfvm.boltzmannTemp", boltzmannTemp);
        TraceStore.put("cfvm.tempSource", "snapshot_restore");
        TraceStore.put("cfvm.tempAnnealApplied", false);
    }

    public record Entry(long patternId, long traceSize, long signatureLength) {
    }
}
