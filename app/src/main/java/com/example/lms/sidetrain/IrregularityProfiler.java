
package com.example.lms.sidetrain;
import org.springframework.stereotype.Component;
@Component
public class IrregularityProfiler {
    public boolean contradicts(String a, String b){
        if (a==null || b==null) return false;
        String x=a.trim().toLowerCase(), y=b.trim().toLowerCase();
        return x.contains("not ") && !y.contains("not ");
    }
}
