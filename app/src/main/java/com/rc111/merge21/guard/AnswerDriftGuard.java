
package com.rc111.merge21.guard;

import com.rc111.merge21.qa.Answer;
import org.springframework.stereotype.Component;

@Component
public class AnswerDriftGuard implements AnswerGuard {
    @Override
    public Answer filter(Answer a) {
        // placeholder: simple heuristic already in pipeline
        return a;
    }
}
