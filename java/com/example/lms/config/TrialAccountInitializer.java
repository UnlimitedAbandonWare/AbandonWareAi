package com.example.lms.config;

import com.example.lms.domain.Administrator;
import com.example.lms.repository.AdministratorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * 초기화 시 애플리케이션에 체험(Trial) 계정을 생성하는 설정 클래스입니다.
 *
 * <p>
 * 기본적으로 시스템은 익명 사용자가 체험 모드로 최대 3회까지 서비스를 사용할 수 있게 합니다. 그러나
 * Spring Security가 내부적으로 Principal 기반 권한을 확인할 때에는 사용자 계정이 존재해야 합니다.
 * 따라서 체험용 계정(trial)을 데이터베이스에 미리 생성해 둡니다. 이 계정은 ROLE_TRIAL 권한을 갖고
 * 있으나 로그인 페이지를 통해 접근하지는 않으며, {@link com.example.lms.api.ChatApiController}
 * 에서는 익명 사용자를 이 계정으로 매핑합니다. 관리자는 별도의 계정 관리 UI에서 이 계정을 볼 수 있지만,
 * 비밀번호는 무작위로 설정되어 직접 로그인할 수 없습니다.
 */
@Configuration
@RequiredArgsConstructor
public class TrialAccountInitializer {
    private static final Logger log = LoggerFactory.getLogger(TrialAccountInitializer.class);

    private final AdministratorRepository adminRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 애플리케이션 기동 시 체험용(trial) 계정을 생성합니다. 이미 존재하면 아무 작업도 하지 않습니다.
     */
    @Bean
    public ApplicationRunner trialAccountRunner() {
        return args -> {
            adminRepository.findByUsername("trial").ifPresentOrElse(
                    a -> log.info("✅ 'trial' 관리자 계정이 이미 존재합니다."),
                    () -> {
                        log.warn("⚠️ 'trial' 관리자 계정이 없어 새로 생성합니다. 로그인에는 사용되지 않습니다.");
                        String randomPassword = UUID.randomUUID().toString();
                        // Create a new Administrator with username, encoded password and name.  The role
                        // will be overwritten below.
                        Administrator trial = new Administrator("trial", passwordEncoder.encode(randomPassword), "trial");
                        trial.setRole("ROLE_TRIAL");
                        adminRepository.save(trial);
                    }
            );
        };
    }
}