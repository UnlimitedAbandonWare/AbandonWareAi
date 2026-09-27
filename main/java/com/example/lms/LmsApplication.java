package com.example.lms;

import ai.abandonware.subagent.SubagentFlowRuntimeConfiguration;
import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.service.AdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication(scanBasePackages = {"com.example.lms", "com.nova.protocol"})
@EnableConfigurationProperties({LlmRouterProperties.class})
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
@Import(SubagentFlowRuntimeConfiguration.class)
public class LmsApplication {
    private static final Logger log = LoggerFactory.getLogger(LmsApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(LmsApplication.class, args);
    }

    @Bean
    public CommandLineRunner init(
            AdminService adminService,
            @Value("${security.bootstrap-admin.password:${LMS_ADMIN_BOOTSTRAP_PASSWORD:}}") String bootstrapPassword) {
        return args -> {
            if (ConfigValueGuards.isMissing(bootstrapPassword)) {
                log.info("[AWX][runtime-config] bootstrap-admin skipped reason=missing_bootstrap_password");
                return;
            }
            adminService.createIfAbsent("admin", bootstrapPassword, "\uCD5C\uACE0\uAD00\uB9AC\uC790");
            log.info("[AWX2AF2][bootstrap] default admin account ready username=admin password=redacted");
        };
    }
}
