
package com.example.lms.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class AutoLearnSchedulingConfig {
    @Bean(name = "autolearnTaskScheduler")
    public ThreadPoolTaskScheduler autolearnTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setRemoveOnCancelPolicy(true);
        s.setThreadNamePrefix("autolearn-");
        return s;
    }
}
