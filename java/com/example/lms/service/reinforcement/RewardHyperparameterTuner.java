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
            RewardScoringEngine engine = builder.build();
            double loss = loss(engine, samples);

            // gradient for wSim
            builder.weights(wSim + step, wHit, wRec);
            double lossSim = loss(builder.build(), samples);
            double gradSim = (lossSim - loss) / step;

            // gradient for wHit
            builder.weights(wSim, wHit + step, wRec);
            double lossHit = loss(builder.build(), samples);
            double gradHit = (lossHit - loss) / step;

            // gradient for wRec
            builder.weights(wSim, wHit, wRec + step);
            double lossRec = loss(builder.build(), samples);
            double gradRec = (lossRec - loss) / step;

            // restore original weights
            builder.weights(wSim, wHit, wRec);

            // update weights
            wSim = wSim - learningRate * gradSim;
            wHit = wHit - learningRate * gradHit;
            wRec = wRec - learningRate * gradRec;
            builder.weights(wSim, wHit, wRec);
        }
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
