package com.example.lms.boot;

import com.example.lms.config.NaverFilterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Logs the initial state of the web search filter configuration at
 * application startup.  Having a single line summarising the domain
 * filter, keyword filter and policy helps operators verify that the
 * desired settings have been applied.  The allowlist size is also
 * reported to aid debugging.
 */
@Component
@RequiredArgsConstructor
public class StartupLogger implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);
    private final NaverFilterProperties props;

    @Override
    public void run(ApplicationArguments args) {
        var list = props.getDomainAllowlist();
        int allowSize = (list == null ? 0 : list.size());
        log.info("[FILTER] domain={}, keyword={}, policy={}, allowlist.size={}",
                props.isEnableDomainFilter(),
                props.isEnableKeywordFilter(),
                props.getDomainPolicy(),
                allowSize);
    }
}