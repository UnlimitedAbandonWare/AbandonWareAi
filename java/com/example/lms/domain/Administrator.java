package com.example.lms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 관리자(Administrator) 엔티티
 * Spring Security의 UserDetails 인터페이스를 구현하여 인증/인가에 사용됩니다.
 */
@Entity
@Table(
        name = "administrators",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_admin_username", columnNames = "username"
        )
)
public class Administrator implements UserDetails {

    /**
     * 기본 키 (AUTO_INCREMENT)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 로그인 ID (username)
     */
    @Column(nullable = false, length = 50, unique = true)
    private String username;

    /**
     * 암호화된 비밀번호
     */
    @Column(nullable = false)
    private String password;

    /**
     * 권한 (기본값: "ROLE_ADMIN")
     */
    @Column(nullable = false, length = 20)
    private String role = "ROLE_ADMIN";

    /**
     * 관리자 이름
     */
    private String name;

    /**
     * 주민등록번호
     */
    private String rrn;

    /**
     * 연락처
     */
    private String phone;

    /**
     * 주소
     */
    private String address;

    /**
     * 생성 일시 (자동 설정)
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * JPA용 기본 생성자
     */
    public Administrator() {}

    /**
     * 편의 생성자
     */
    public Administrator(String username, String password, String name) {
        this.username = username;
        this.password = password;
        this.name     = name;
    }

    // --- UserDetails 인터페이스 구현 메서드 ---

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 'role' 필드 값을 기반으로 권한 목록을 생성합니다.
        // "ROLE_" 접두사는 Spring Security의 규칙이므로, DB에 저장된 값에 접두사가 없다면 추가해야 합니다.
        // 현재 role 필드의 기본값이 "ROLE_ADMIN"이므로 그대로 사용합니다.
        return List.of(new SimpleGrantedAuthority(this.role));
    }

    // getUsername()과 getPassword()는 필드 getter가 UserDetails의 요구사항을 충족합니다.
    @Override
    public String getUsername() {
        return this.username;
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true; // 계정 만료 여부 (true: 만료되지 않음)
    }

    @Override
    public boolean isAccountNonLocked() {
        return true; // 계정 잠김 여부 (true: 잠기지 않음)
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true; // 자격 증명(비밀번호) 만료 여부 (true: 만료되지 않음)
    }

    @Override
    public boolean isEnabled() {
        return true; // 계정 활성화 여부 (true: 활성화됨)
    }


    // --- 기존 Getter / Setter ---

    public Long getId() { return id; }

    public void setUsername(String username) { this.username = username; }

    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRrn() { return rrn; }
    public void setRrn(String rrn) { this.rrn = rrn; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}