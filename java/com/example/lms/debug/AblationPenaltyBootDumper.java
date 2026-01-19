package com.example.lms.debug;

import com.example.lms.trace.LogCorrelation;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs the effective ablation penalty values once per boot (active-profile merged).
 */
@Component
public class AblationPenaltyBootDumper implements ApplicationListener<ApplicationReadyEvent> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AblationPenaltyBootDumper.class);

    private final Environment env;

    public AblationPenaltyBootDumper(Environment env) {
        this.env = env;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            Map<String, Object> penalties = new LinkedHashMap<>();
            penalties.put("default", d("uaw.ablation.penalty.default", 0.12));
            penalties.put("websearch.base", d("uaw.ablation.penalty.websearch.base",
                    env.getProperty("uaw.ablation.penalty.websearch", Double.class, 0.35)));
            penalties.put("websearch.starvation", d("uaw.ablation.penalty.websearch.starvation", 0.28));
            penalties.put("websearch.domain-misroute", d("uaw.ablation.penalty.websearch.domain-misroute", 0.22));
            penalties.put("query-transformer", d("uaw.ablation.penalty.query-transformer", 0.18));
            penalties.put("retrieval", d("uaw.ablation.penalty.retrieval", 0.20));
            penalties.put("rerank", d("uaw.ablation.penalty.rerank", 0.15));

            log.info("[AblationPenalty] activeProfiles={} effective={}{}",
                    String.join(",", env.getActiveProfiles()),
                    penalties,
                    LogCorrelation.suffix());
        } catch (Throwable t) {
            // fail-soft
            log.debug("[AblationPenalty] dump skipped due to {}{}", t.toString(), LogCorrelation.suffix());
        }
    }

    private Double d(String key, double fallback) {
        return env.getProperty(key, Double.class, fallback);
    }
}
