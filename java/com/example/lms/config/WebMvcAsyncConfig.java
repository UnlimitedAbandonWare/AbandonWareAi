package com.example.lms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.lms.infra.exec.ContextAwareTaskDecorator;

/**
 * Spring MVC async request executor tuning.
 *
 * <p>Fixes production warnings about the default SimpleAsyncTaskExecutor
 * and prevents unbounded thread growth under load (SSE/async controllers).</p>
 */
@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    @Bean(name = "mvcAsyncTaskExecutor")
    public AsyncTaskExecutor mvcAsyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mvc-async-");
        executor.setTaskDecorator(new ContextAwareTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncTaskExecutor());
        configurer.setDefaultTimeout(30_000L);
    }
}
