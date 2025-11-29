
package com.rc111.merge21.guard;

import com.rc111.merge21.qa.Answer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FinalSigmoidGate implements AnswerGuard {
    private final double k;
    private final double x0;

    public FinalSigmoidGate(@Value("${gate.finalSigmoid.k:12.0}") double k,
                            @Value("${gate.finalSigmoid.x0:0.0}") double x0) {
        this.k = k; this.x0 = x0;
    }

    private double sigmoid(double x) { return 1.0 / (1.0 + Math.exp(-k * (x - x0))); }

    @Override
    public Answer filter(Answer a) {
        double p = sigmoid(a.confidence);
        if (p < 0.5) a.status = Answer.Status.LOW_CONFIDENCE;
        return a;
    }
}
