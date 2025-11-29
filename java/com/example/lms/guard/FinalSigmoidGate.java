package com.example.lms.guard;

/**
 * Final sigmoid gate that maps risk signals to a single score and decision.
 */
public class FinalSigmoidGate {

    public double score(double hallucinationRisk, double policyRisk, double lowCitationPenalty) {
        double z = 3.0 - (2.0*hallucinationRisk + 1.5*policyRisk + 0.5*lowCitationPenalty);
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public boolean allow(double s) {
        double th = Double.parseDouble(System.getProperty("guard.final.threshold", "0.5"));
        return s >= th;
    }
}