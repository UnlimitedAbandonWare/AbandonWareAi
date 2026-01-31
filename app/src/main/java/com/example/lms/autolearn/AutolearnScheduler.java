
package com.example.lms.autolearn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.lms.config.AutoLearnProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "autolearn", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AutolearnScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutolearnScheduler.class);
    private final AutoLearnProperties props;
    private final IdleDetector idleDetector;
    private final AutolearnService service;
    public AutolearnScheduler(AutoLearnProperties props, IdleDetector idleDetector, AutolearnService service){
        this.props = props; this.idleDetector = idleDetector; this.service = service;
    }

    @Scheduled(fixedDelayString = "#{@autoLearnProperties.schedule.fixedDelayMs}", scheduler = "autolearnTaskScheduler")
    public void tick(){
        if (!props.isEnabled()) return;
        if (idleDetector.isIdle()) {
            log.info("Idle detected. Running AutoLearn cycle.");
            try { service.beginAutoLearnCycle(); }
            catch (Exception e){ log.warn("AutoLearn cycle error: {}", e.toString()); }
        } else {
            log.debug("Skip AutoLearn: not idle.");
        }
    }
}
