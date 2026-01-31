
package com.example.lms.autolearn;
import com.example.lms.config.AutoLearnProperties;
import com.example.lms.context.ActiveRequestsCounter;
import org.springframework.stereotype.Component;
import java.lang.management.ManagementFactory;

@Component
public class IdleDetector {
    private final AutoLearnProperties props;
    private final ActiveRequestsCounter counter;
    public IdleDetector(AutoLearnProperties props, ActiveRequestsCounter counter){
        this.props = props; this.counter = counter;
    }
    public boolean isIdle(){
        long now = System.currentTimeMillis();
        long quietMs = props.getIdle().getQuietSeconds() * 1000L;
        boolean noRequestsRecently = counter.get() == 0 && (now - counter.getLastRequestFinishedAt()) >= quietMs;
        double cpu = systemCpuLoad();
        boolean lowCpu = cpu >= 0 ? cpu < props.getIdle().getCpuThreshold() : true;
        return noRequestsRecently && lowCpu;
    }
    private double systemCpuLoad(){
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                return ((com.sun.management.OperatingSystemMXBean) os).getSystemCpuLoad();
            }
        } catch (Throwable ignored){}
        return -1.0; // unknown
    }
}
