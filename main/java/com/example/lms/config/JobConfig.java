package com.example.lms.config;

import com.example.lms.jobs.InMemoryJobService;
import com.example.lms.jobs.JobService;
import com.example.lms.jobs.JdbcJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class JobConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "jobs.storage", havingValue = "jdbc", matchIfMissing = true)
    public JobService jobService(DataSource dataSource, ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            @Value("${jobs.enabled-types:}") String enabledTypes,
            @Value("${jobs.callback-enabled-types:}") String callbackEnabledTypes) {
        return new JdbcJobService(dataSource, mapper, Clock.systemUTC(), transactionManager,
                types(enabledTypes), types(callbackEnabledTypes));
    }

    private static Set<String> types(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Bean
    @ConditionalOnProperty(name = "jobs.storage", havingValue = "memory")
    public JobService developmentJobService() {
        return new InMemoryJobService();
    }
}
