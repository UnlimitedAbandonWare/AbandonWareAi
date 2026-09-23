package com.example.lms.security;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.User;
import com.example.lms.repository.AdministratorRepository;
import com.example.lms.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final AdministratorRepository adminRepo;
    private final @Lazy UserService userService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return adminRepo.findByUsername(username)
                .map(this::toUserDetails)
                .or(() -> userService.findByUsername(username).map(this::toUserDetails))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private UserDetails toUserDetails(User user) {
        return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password(user.getPassword())
                .roles(normalizeRole(user.getRole(), user.getUsername(), "User"))
                .build();
    }

    private UserDetails toUserDetails(Administrator administrator) {
        return org.springframework.security.core.userdetails.User.withUsername(administrator.getUsername())
                .password(administrator.getPassword())
                .roles(normalizeRole(administrator.getRole(), administrator.getUsername(), "Administrator"))
                .build();
    }

    private String normalizeRole(String rawRole, String id, String who) {
        if (rawRole == null || rawRole.isBlank()) {
            throw new IllegalStateException(who + " [" + id + "] has no role");
        }
        return rawRole.startsWith("ROLE_") ? rawRole.substring(5) : rawRole;
    }
}
