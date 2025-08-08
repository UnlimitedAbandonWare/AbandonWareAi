package com.example.lms.domain;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.ChatMessage;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
// 필요시 상단 import 추가
import java.util.Comparator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자-AI 간의 대화 세션을 나타내는 JPA 엔티티입니다.
 * 어떤 관리자(Administrator)의 대화인지 추적하며, 대화 메시지(ChatMessage) 목록을 가집니다.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSession {

    /**
     * 세션의 고유 ID (Primary Key)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 대화의 제목 (보통 첫 번째 메시지로 생성)
     */
    @Column(nullable = false, length = 120)
    private String title;

    /**
     * 세션 생성 일시 (자동 생성)
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * [수정] 이 세션의 소유자인 관리자(Administrator) 정보.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false)
    private Administrator administrator;

    /**
     * 세션에 포함된 대화 메시지 목록.
     * 세션이 삭제되면 메시지도 함께 삭제됩니다 (cascade = ALL, orphanRemoval = true).
     */

    @OneToMany(mappedBy = "session", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC, id ASC")
    private List<ChatMessage> messages = new ArrayList<>();



    // ────────────────── 편의 생성자 ──────────────────

    /**
     * 제목만으로 세션을 생성하는 편의 생성자.
     * 주의: administrator 필드가 null이므로, 사용 시 반드시 설정해야 합니다.
     */
    public ChatSession(String title) {
        this.title = title;
    }

    /**
     * 제목과 소유 관리자 정보로 세션을 생성하는 생성자.
     * @param title 세션 제목
     * @param administrator 소유 관리자
     */
    public ChatSession(String title, Administrator administrator) {
        this.title = title;
        this.administrator = administrator;
    }

    // ────────────────── 편의 메서드 ──────────────────

    /**
     * 세션에 새로운 메시지를 추가하고, 메시지 객체에도 현재 세션을 설정하여 양방향 관계를 유지합니다.
     * @param msg 추가할 ChatMessage 객체
     */
    public void addMessage(ChatMessage msg) {
        this.messages.add(msg);
        msg.setSession(this);
    }
}
