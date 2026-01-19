// src/main/java/com/example/lms/domain/Professor.java
package com.example.lms.domain;

import com.example.lms.domain.Course;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;




/**
 * 교수(Professor) 엔티티
 *
 * <pre>
 *   • 2025-06-13 : username 컬럼 추가
 *   • 2025-06-13 : (String name, String email) 2-파라미터 생성자 추가
 *   • 2025-06-13 : 기본 생성자 접근 제어자를 <b>public</b> 으로 변경  ← ★ (방법 ①)
 * </pre>
 */
@Entity
@Table(name = "professors",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "email"),
                @UniqueConstraint(columnNames = "username")
        })
public class Professor {

    /* ─────────────── PK ─────────────── */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* ─────────────── 인증 정보 ─────────────── */
    @Column(nullable = false, unique = true, length = 50)
    private String username;          // 로그인 ID

    @Column(nullable = false, unique = true, length = 100)
    private String email;             // 이메일(ID 겸용)

    @Column(nullable = false)
    private String password;

    /* ─────────────── 프로필 정보 ─────────────── */
    @Column(nullable = false, length = 20)
    private String role = "ROLE_PROF";

    @Column(nullable = false, length = 50)
    private String name;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /* ─────────────── 연관 관계 ─────────────── */
    @OneToMany(mappedBy = "professor",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<Course> courses = new ArrayList<>();

    /* ─────────────── 생성자 ─────────────── */
    /** JPA & Thymeleaf 바인딩용 - 이제 <b>public</b> */
    public Professor() { }

    /** 전체 필드 생성자 */
    public Professor(String username,
                     String email,
                     String password,
                     String name) {
        this.username = username;
        this.email    = email;
        this.password = password;
        this.name     = name;
    }

    /** (username, email, name) - password 공백 */
    public Professor(String username,
                     String email,
                     String name) {
        this(username, email, "", name);
    }

    /** (name, email) - 서비스·테스트 호환용 */
    public Professor(String name, String email) {
        this(usernameFrom(email), email, "", name);
    }

    private static String usernameFrom(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    /* ─────────────── 게터 / 세터 ─────────────── */
    public Long   getId()       { return id; }

    public String getUsername() { return username; }
    public void   setUsername(String username) { this.username = username; }

    public String getEmail()    { return email; }
    public void   setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void   setPassword(String password) { this.password = password; }

    public String getRole()     { return role; }
    public void   setRole(String role) { this.role = role; }

    public String getName()     { return name; }
    public void   setName(String name) { this.name = name; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public List<Course> getCourses()    { return courses; }
}