package com.example.lms.domain;

import com.example.lms.domain.ChatMessage;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter @Setter
@NoArgsConstructor                // JPA 프록시용
@AllArgsConstructor               // 빌더용
@Builder
public class ChatSession {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "session",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @JsonManagedReference
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    /* 편의 생성자 ─ ChatHistoryService에서 사용 */      // ★
    public ChatSession(String title) {                   // ★
        this.title = title;                              // ★
    }                                                    // ★

    public void addMessage(ChatMessage msg) {
        messages.add(msg);
        msg.setSession(this);
    }
}
