// src/main/java/com/example/lms/LmsApplication.java
package com.example.lms;

import com.example.lms.service.AdminService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan // com.example.lms 하위의 @ConfigurationProperties 전부 스캔
@EnableScheduling
@EnableAsync
public class LmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LmsApplication.class, args);
    }

    @Bean
    public CommandLineRunner init(AdminService adminService) {
        return args -> {
            try {
                adminService.create("admin", "aa0526", "최고관리자");
                System.out.println("▶ 기본 관리자 계정 생성: admin / 비밀번호 aa0526");
            } catch (Exception ignore) { /* 이미 있으면 패스 */ }
        };
    }
}