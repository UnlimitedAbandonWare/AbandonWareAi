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
     * 대용량 메시지 저장 컬럼.
     *
     * <p>과거에는 MariaDB/MySQL에서 {@code TEXT}로 강제했지만,
     * Search Trace(HTML) / RSUM(rolling summary) 같은 시스템 메타가 커지면
     * {@code Data too long for column 'content'} 오류가 발생할 수 있습니다.
     *
     * <p>따라서 dialect가 적절한 LOB 타입(CLOB/LONGTEXT 등)을 선택하도록
     * {@link jakarta.persistence.Lob} 매핑만 사용합니다.
     */
    @Lob
    @Column(nullable = false)
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