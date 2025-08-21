// src/main/java/com/example/lms/LmsApplication.java
package com.example.lms;

import com.example.lms.config.ModelProperties;
import com.example.lms.config.MoeRoutingProps;
import com.example.lms.config.GoogleTranslateProperties; // ← 필요하면 포함
import com.example.lms.config.KakaoProperties;
import com.example.lms.config.TmapProperties;
import com.example.lms.config.LocationFeatureProperties;
import com.example.lms.service.AdminService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication                      // ✅ 한 번만
@ConfigurationPropertiesScan(basePackageClasses = {
        ModelProperties.class,
        MoeRoutingProps.class,
        GoogleTranslateProperties.class     // ✅ 이것도 스캔에 포함(있다면)
})
@EnableScheduling
@EnableAsync
@org.springframework.boot.context.properties.EnableConfigurationProperties({KakaoProperties.class, TmapProperties.class, LocationFeatureProperties.class})
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