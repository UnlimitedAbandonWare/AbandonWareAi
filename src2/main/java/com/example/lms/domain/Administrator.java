// src/main/java/com/example/lms/domain/Administrator.java
package com.example.lms.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

/**
 * 관리자(Administrator) 엔티티
 */
@Entity
@Table(
        name = "administrators",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_admin_username", columnNames = "username"
        )
)
public class Administrator {

    /**
     * 기본 키 (AUTO_INCREMENT)
     */

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 로그인 ID (username)
     */
    @Column(nullable = false, length = 50)
    private String username;

    /**
     * 암호화된 비밀번호
     */
    @Column(nullable = false)
    private String password;

    /**
     * 권한 (기본: ROLE_ADMIN)
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
     * JPA용 기본 생성자 (public)
     */
    public Administrator() {}

    /**
     * 편의 생성자: username, 암호화된 password, name
     */
    public Administrator(String username, String password, String name) {
        this.username = username;
        this.password = password;
        this.name     = name;
    }

    // --- getter / setter ---
    public Long getId() { return id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
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
