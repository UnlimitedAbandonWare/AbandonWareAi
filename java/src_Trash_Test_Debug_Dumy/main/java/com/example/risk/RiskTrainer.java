package com.example.risk;

import com.example.lms.ml.SoftmaxClassifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Performs one-epoch batch training of a {@link SoftmaxClassifier} for risk
 * prediction.  The trainer extracts features from each labelled context,
 * constructs the input matrix and labels, computes class weights to
 * mitigate class imbalance, and fits the classifier via {@link
 * SoftmaxClassifier#fitBatch(double[][], int[], double[])}.  A new
 * classifier is initialised via {@link RiskModelProvider#ensure(int)}
 * when none exists.  The method returns the loss of the epoch.
 */
@Service
public class RiskTrainer {
    private final RiskFeatureExtractor fx;
    private final RiskModelProvider provider;

    public RiskTrainer(RiskFeatureExtractor fx, RiskModelProvider provider) {
        this.fx = fx;
        this.provider = provider;
    }

    /**
     * Train a single epoch on the provided batch.  Returns the loss.
     *
     * @param batch list of labelled contexts; may be empty or null
     * @return loss value from classifier fit; 0.0 if no training occurred
     */
    public double trainEpoch(List<LabeledContext> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0.0;
        }
        int n = batch.size();
        double[][] X = new double[n][];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            X[i] = fx.featuresOf(batch.get(i).ctx());
            y[i] = batch.get(i).label();
        }
        SoftmaxClassifier clf = provider.ensure(X[0].length);
        // Compute positive class frequency for class weighting
        double pos = 0;
        for (int yi : y) {
            pos += (yi == 1 ? 1 : 0);
        }
        double total = Math.max(1, y.length);
        double[] cw = new double[]{ (total - pos) / total, pos / total };
        return clf.fitBatch(X, y, cw);
    }

    /**
     * Simple record combining a context with a binary label.  The label
     * should be 1 for risky contexts and 0 otherwise.
     */
    public record LabeledContext(ListingContext ctx, int label) {}
}