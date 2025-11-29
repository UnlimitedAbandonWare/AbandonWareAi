package com.example.lms.telemetry;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Configuration component that logs the presence or absence of important
 * configuration keys at startup.  In accordance with the key preservation
 * policy, this bean does not alter any properties; it simply records which
 * keys are present so that operators can verify expected settings via logs.
 */
@Configuration
@RequiredArgsConstructor
public class ConfigKeysLogger {
    private static final Logger log = LoggerFactory.getLogger(ConfigKeysLogger.class);
    private final Environment env;

    // List of keys considered interesting for diagnostics.  Modify this list
    // to add additional property keys that should be inspected at startup.
    private final List<String> interestingKeys = List.of(
            "router.moe.high",
            "router.moe.mini",
            "router.allow-header-override",
            "threshold",
            "margin",
            "rrf.k",
            "rerank.ce.topK",
            "authority.tier-weights",
            "scoring.path-alignment.enabled"
    );

    @Bean
    public ApplicationRunner logConfigKeys() {
        return args -> {
            for (String k : interestingKeys) {
                boolean present = env.containsProperty(k);
                log.debug("[config] {} {}", present ? "present" : "missing", k);
            }
        };
    }
}