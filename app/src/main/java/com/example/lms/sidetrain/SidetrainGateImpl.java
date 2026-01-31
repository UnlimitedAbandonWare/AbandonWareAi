
package com.example.lms.sidetrain;
import org.springframework.stereotype.Component;
@Component
public class SidetrainGateImpl {
    public boolean allow(boolean auditorConsistency){ return auditorConsistency; }
}
