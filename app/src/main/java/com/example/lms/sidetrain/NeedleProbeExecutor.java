
package com.example.lms.sidetrain;
import org.springframework.stereotype.Component;
@Component
public class NeedleProbeExecutor {
    public String extractKeyFactQuestion(String answer){
        if (answer==null||answer.isEmpty()) return null;
        int idx = answer.indexOf('.');
        String fact = idx>0 ? answer.substring(0, idx) : answer;
        return "Verify: " + fact;
    }
}
