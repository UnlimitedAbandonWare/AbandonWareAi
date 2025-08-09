// src/main/java/com/example/lms/LmsApplication.java
package com.example.lms;

import com.example.lms.config.GoogleTranslateProperties;
import com.example.lms.service.AdminService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;       // ← (추가)
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync                              // ✨ 바로 이것!  (@Async 실행용)
@EnableConfigurationProperties(GoogleTranslateProperties.class)
public class LmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LmsApplication.class, args);
    }

    /** 애플리케이션 시작 시 기본 관리자 계정 생성 */
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
