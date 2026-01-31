package com.example.lms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.service.AdminService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({LlmRouterProperties.class})
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class LmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LmsApplication.class, args);
    }

    @Bean
    public CommandLineRunner init(AdminService adminService) {
        return args -> {
            // idempotent bootstrap of an admin account
            adminService.createIfAbsent("admin", "aa0808", "최고관리자");
            System.out.println("▶ 기본 관리자 계정 준비 완료: admin / 비밀번호 aa0808");
        };
    }
}
