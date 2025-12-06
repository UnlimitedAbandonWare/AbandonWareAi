package com.example.lms.config;

import com.example.lms.jobs.*;
import org.springframework.context.annotation.*;

@Configuration
public class JobConfig {
    @Bean JobService jobService(){ return new InMemoryJobService(); }
}