
package com.rc111.merge21.guard;

import com.rc111.merge21.qa.Answer;
import org.springframework.stereotype.Component;

@Component
public class OverdriveGuard implements AnswerGuard {
    @Override
    public Answer filter(Answer a) {
        // placeholder: could adjust tokens/temperature dynamically
        return a;
    }
}
