
package com.example.lms.sidetrain;
import org.springframework.stereotype.Component;
@Component
public class SidetrainAuditor {
    private final IrregularityProfiler profiler;
    public SidetrainAuditor(IrregularityProfiler p){ this.profiler = p; }
    public boolean consistent(String original, String detour, String needle){
        return !profiler.contradicts(original, detour) && !profiler.contradicts(original, needle);
    }
}
