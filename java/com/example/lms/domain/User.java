// src/main/java/com/example/lms/domain/User.java
package com.example.lms.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;  // ex. ROLE_USER

    @Column(nullable = false, length = 100)
    private String email;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected User() {}

    public User(String username, String password, String role, String email) {
        this.username = username;
        this.password = password;
        this.role     = role;
        this.email    = email;
    }

    // getter / setter
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String u) { this.username = u; }
    public String getPassword() { return password; }
    public void setPassword(String p) { this.password = p; }
    public String getRole() { return role; }
    public void setRole(String r) { this.role = r; }
    public String getEmail() { return email; }
    public void setEmail(String e) { this.email = e; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
