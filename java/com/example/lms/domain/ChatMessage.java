package com.example.lms.domain;

import com.example.lms.domain.ChatSession;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;




@Entity
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* ────────── FK (세션) ────────── */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @JsonBackReference
    private ChatSession session;

    /* ────────── 역할 & 내용 ────────── */
    @Column(nullable = false, length = 20)
    private String role;               // user | assistant | system

    /**
     * ① @Lob → 대용량 문자열임을 JPA에 알림
     * ② columnDefinition="TEXT" → Hibernate 가 DDL 생성 시
     *    명시적으로 TEXT 타입을 사용하도록 강제 (utf8mb4 가능)
     */
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /* ────────── 메타 ────────── */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /* 편의 생성자 (ChatHistoryService 등에서 사용) */
    public ChatMessage(ChatSession session, String role, String content) {
        this.session = session;
        this.role    = role.toLowerCase();   // OpenAI 권장: 소문자
        this.content = content;
    }
}