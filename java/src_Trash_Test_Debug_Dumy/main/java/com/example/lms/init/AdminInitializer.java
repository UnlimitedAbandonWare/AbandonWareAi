package com.example.lms.init;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.Role;
import com.example.lms.repository.AdministratorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Initializes a default administrator account on application startup.
 *
 * <p>This component checks whether an administrator with the username
 * {@code admin} already exists. If not, it creates one using a
 * default password. This ensures that there is always at least one
 * administrative account available after a fresh installation or
 * database reset.</p>
 */
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    /** Repository used to query and persist administrator entities. */
    private final AdministratorRepository repo;

    /** Password encoder used to securely store administrator passwords. */
    private final PasswordEncoder encoder;

    /**
     * Runs on application startup and creates a default administrator
     * account if one does not already exist.
     *
     * @param args application arguments (unused)
     */
    @Override
    public void run(ApplicationArguments args) {
        // Skip insertion if an administrator with the username 'admin' already exists
        if (repo.findByUsername("admin").isPresent()) {
            return;
        }

        // Build and persist a new administrator record with default credentials
        Administrator admin = new Administrator();
        admin.setUsername("admin");
        admin.setPassword(encoder.encode("admin1234"));
        admin.setRole("ROLE_ADMIN");
        repo.save(admin);
    }
}