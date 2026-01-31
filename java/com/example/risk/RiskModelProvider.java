package com.example.risk;


/**
 * Port for providing a classification model used by risk scorer.
 */
public interface RiskModelProvider {
    /**
     * Returns the classifier instance. May return null if no model is available.
     */
    Classifier get();

    /**
     * Classification model API exposing probability prediction.
     */
    interface Classifier {
        /**
         * Returns probability vector [p0, p1, /* ... *&#47;] for classes.
         *
         * @param x input feature vector
         * @return probabilities across classes
         */
        double[] predictProba(double[] x);
    }
}