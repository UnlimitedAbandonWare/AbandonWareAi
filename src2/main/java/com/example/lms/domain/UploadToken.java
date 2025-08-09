// src/main/java/com/example/lms/domain/UploadToken.java
package com.example.lms.domain;

import com.example.lms.domain.Assignment;
import com.example.lms.domain.Student;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "upload_tokens",
        indexes = @Index(columnList = "token", unique = true)
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class UploadToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, unique = true, nullable = false)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private Assignment assignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 만료 여부 확인
     */
    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }
}
