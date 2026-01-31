package com.example.lms.init;/*// src/main/java/com/example/lms/init/AdminInitializer.java
import com.example.lms.domain.Administrator;
import com.example.lms.domain.Role;
import com.example.lms.repository.AdministratorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;



@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    private final AdministratorRepository repo;
    private final PasswordEncoder encoder;

    @Override
    public void run(ApplicationArguments args) {
        // 이미 'admin' 계정이 있으면 삽입을 건너뜀
        if (repo.existsByUsername("admin")) {
            return;
        }

        Administrator admin = Administrator.builder()
                .username("admin")
                .password(encoder.encode("admin123"))
                .role(Role.ADMIN)
                .build();
        repo.save(admin);
    }
}
*/