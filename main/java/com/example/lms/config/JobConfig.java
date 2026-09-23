package com.example.lms.config;

import com.example.lms.jobs.InMemoryJobService;
import com.example.lms.jobs.JobService;
import com.example.lms.jobs.JdbcJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JobConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "jobs.storage", havingValue = "jdbc", matchIfMissing = true)
    public JobService jobService(DataSource dataSource, ObjectMapper mapper) {
        return new JdbcJobService(dataSource, mapper, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "jobs.storage", havingValue = "memory")
    public JobService developmentJobService() {
        return new InMemoryJobService();
    }
}
