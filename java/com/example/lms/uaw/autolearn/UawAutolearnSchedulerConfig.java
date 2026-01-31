package com.example.lms.uaw.autolearn;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class UawAutolearnSchedulerConfig {

    @Bean(name = "uawAutolearnTaskScheduler")
    public ThreadPoolTaskScheduler uawAutolearnTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("uaw-autolearn-");
        s.setRemoveOnCancelPolicy(true);
        return s;
    }
}
