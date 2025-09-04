package com.example.lms.boot;

import com.example.lms.config.NaverFilterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Logs the initial state of the web search filter configuration at
 * application startup.  Having a single line summarising the domain
 * filter, keyword filter and policy helps operators verify that the
 * desired settings have been applied.  The allowlist size is also
 * reported to aid debugging.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupLogger implements ApplicationRunner {
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