package com.example.lms.debug;

import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.search.TraceStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Logs the effective ablation penalty values once per boot (active-profile merged).
 */
@Component
public class AblationPenaltyBootDumper implements ApplicationListener<ApplicationReadyEvent> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AblationPenaltyBootDumper.class);
    private static final double START_SCORE = 1.0d;
    private static final AtomicReference<Map<String, Object>> EFFECTIVE_PENALTIES =
            new AtomicReference<>(Map.of());

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

            Map<String, Object> snapshot = Map.copyOf(penalties);
            EFFECTIVE_PENALTIES.set(snapshot);
            TraceStore.put("ablation.penalties", snapshot);
            seedScoreBaselineIfMissing();
            log.info("[AblationPenalty] activeProfiles={} effective={}{}",
                    String.join(",", env.getActiveProfiles()),
                    penalties,
                    LogCorrelation.suffix());
        } catch (Throwable t) {
            // fail-soft
            log.debug("[AblationPenalty] dump skipped errorHash={} errorLength={}{}",
                    SafeRedactor.hashValue(messageOf(t)), messageLength(t), LogCorrelation.suffix());
        }
    }

    private Double d(String key, double fallback) {
        return env.getProperty(key, Double.class, fallback);
    }

    public static void seedCurrentTrace() {
        Map<String, Object> penalties = EFFECTIVE_PENALTIES.get();
        TraceStore.putIfAbsent("ablation.penalties", penalties == null ? Map.of() : penalties);
        seedScoreBaselineIfMissing();
    }

    private static void seedScoreBaselineIfMissing() {
        TraceStore.putIfAbsent("ablation.score.current", START_SCORE);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
