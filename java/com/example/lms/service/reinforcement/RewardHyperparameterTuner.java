package com.example.lms.service.reinforcement;

import com.example.lms.service.reinforcement.RewardScoringEngine;
import com.example.lms.service.reinforcement.RewardScoringEngine.Builder;
import com.example.lms.entity.TranslationMemory;
import java.util.List;
import java.time.LocalDateTime;


/**
 * A simple hyperparameter tuner that uses numerical differentiation and gradient descent
 * to adjust the weights of a {@link RewardScoringEngine.Builder} based on a set of
 * labelled training samples.
 */
public class RewardHyperparameterTuner {

    /** A training sample consisting of a memory snippet, a query, a similarity score and a target label. */
    public static class Sample {
        private final TranslationMemory mem;
        private final String query;
        private final double similarity;
        private final double label;

        public Sample(TranslationMemory mem, String query, double similarity, double label) {
            this.mem = mem;
            this.query = query;
            this.similarity = similarity;
            this.label = label;
        }

        public TranslationMemory getMem() { return mem; }
        public String getQuery() { return query; }
        public double getSimilarity() { return similarity; }
        public double getLabel() { return label; }
    }

    private final Builder builder;
    private final double learningRate;
    private final double step;

    public RewardHyperparameterTuner(RewardScoringEngine.Builder builder, double learningRate, double step) {
        this.builder = builder;
        this.learningRate = learningRate;
        this.step = step;
    }

    public RewardHyperparameterTuner(RewardScoringEngine.Builder builder, double learningRate) {
        this(builder, learningRate, 1e-4);
    }

    /**
     * Tune the weights on the builder using the provided training samples.  The builder's
     * weights will be updated in place.  Normalisation is temporarily disabled during
     * tuning.
     *
     * @param samples the training samples
     * @param iterations the number of gradient descent steps
     * @return a new {@link RewardScoringEngine} with tuned parameters
     */
    public RewardScoringEngine tune(List<Sample> samples, int iterations) {
        builder.normaliseWeights(false);
        double wSim = builder.getWeightSim();
        double wHit = builder.getWeightHit();
        double wRec = builder.getWeightRec();

        for (int i = 0; i < iterations; i++) {
            // 중앙 차분 수치미분 (더 낮은 바이어스)
// d/dw ≈ [L(w+h) - L(w-h)] / (2h)
            builder.weights(wSim + step, wHit, wRec);
            double lossSimPlus  = loss(builder.build(), samples);
            builder.weights(wSim - step, wHit, wRec);
            double lossSimMinus = loss(builder.build(), samples);
            double gradSim = (lossSimPlus - lossSimMinus) / (2 * step);

            builder.weights(wSim, wHit + step, wRec);
            double lossHitPlus  = loss(builder.build(), samples);
            builder.weights(wSim, wHit - step, wRec);
            double lossHitMinus = loss(builder.build(), samples);
            double gradHit = (lossHitPlus - lossHitMinus) / (2 * step);

            builder.weights(wSim, wHit, wRec + step);
            double lossRecPlus  = loss(builder.build(), samples);
            builder.weights(wSim, wHit, wRec - step);
            double lossRecMinus = loss(builder.build(), samples);
            double gradRec = (lossRecPlus - lossRecMinus) / (2 * step);

// 복구
            builder.weights(wSim, wHit, wRec);

            // L2 규제(작은 λ) 포함한 경사하강
            double lambda = 1e-4;
            wSim = wSim - learningRate * (gradSim + 2 * lambda * wSim);
            wHit = wHit - learningRate * (gradHit + 2 * lambda * wHit);
            wRec = wRec - learningRate * (gradRec + 2 * lambda * wRec);
            builder.weights(wSim, wHit, wRec);
        }
        builder.normaliseWeights(true); // 최종 정규화 복원
        return builder.build();
    }

    private double loss(RewardScoringEngine engine, List<Sample> samples) {
        double sum = 0.0;
        for (Sample s : samples) {
            TranslationMemory mem = new TranslationMemory();
            mem.setHitCount(s.getMem() != null ? s.getMem().getHitCount() : 0);
            mem.setCreatedAt(LocalDateTime.now());
            double predicted = engine.reinforce(mem, s.getQuery(), s.getSimilarity());
            double diff = predicted - s.getLabel();
            sum += diff * diff;
        }
        return sum / (samples.isEmpty() ? 1 : samples.size());
    }




    /* ---------- accessors (Builder 위임) ---------- */
    public double getWeightSim() { return builder.getWeightSim(); }
    public double getWeightHit() { return builder.getWeightHit(); }
    public double getWeightRec() { return builder.getWeightRec(); }

}