package com.example.lms.domain;

import com.example.lms.domain.Administrator;
import com.example.lms.domain.NoticeType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;




@Entity
@Table(name = "notices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    private NoticeType type;

    /** 학과 공지일 때만 사용 */
    private Long targetDeptId;

    /** 개인 공지일 때만 사용 */
    private Long targetUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false)
    private Administrator createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 편의 생성자: 공지 등록 시 자주 쓰이는 파라미터만 받음
     */
    public Notice(String title,
                  String content,
                  NoticeType type,
                  Administrator createdBy,
                  Long targetDeptId,
                  Long targetUserId) {
        this.title        = title;
        this.content      = content;
        this.type         = type;
        this.createdBy    = createdBy;
        this.targetDeptId = targetDeptId;
        this.targetUserId = targetUserId;
    }
}