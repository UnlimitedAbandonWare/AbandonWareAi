
package com.example.lms.context;
import org.springframework.stereotype.Component;

@Component
public class AutoLearnContext {
    private static final ThreadLocal<Boolean> TL = ThreadLocal.withInitial(() -> Boolean.FALSE);
    public void begin(){ TL.set(Boolean.TRUE); }
    public void end(){ TL.set(Boolean.FALSE); }
    public boolean isAutoLearn(){ return Boolean.TRUE.equals(TL.get()); }
}
