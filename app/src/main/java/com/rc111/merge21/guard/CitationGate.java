
package com.rc111.merge21.guard;

import com.rc111.merge21.qa.Answer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CitationGate implements AnswerGuard {
    private final int min;

    public CitationGate(@Value("${gate.citation.min:3}") int min) { this.min = min; }

    @Override
    public Answer filter(Answer a) {
        if (a.citations == null || a.citations.length < min) {
            a.status = Answer.Status.NEED_MORE_CONTEXT;
        }
        return a;
    }
}
