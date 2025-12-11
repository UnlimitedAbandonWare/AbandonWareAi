
package com.example.lms.sidetrain;
import org.springframework.stereotype.Component;
@Component
public class DetourSynthesizer {
    public String rephrase(String q){
        if (q==null) return null;
        return "Rephrased: " + q;
    }
}
