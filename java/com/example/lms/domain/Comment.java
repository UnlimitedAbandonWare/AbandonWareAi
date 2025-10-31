// src/main/java/com/example/lms/domain/Comment.java
package com.example.lms.domain;

import com.example.lms.domain.Course;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;




@Entity
@Table(name = "comments")
@Getter @Setter @NoArgsConstructor
public class Comment {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false, length = 100)
    private String author;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /* ─── 관계 ─────────────────────────────── */

    /** ↩ 다수 댓글 → 하나의 강의 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** ↩ (선택) 이 댓글이 달린 부모 댓글 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Comment parent;

    /** ↪ 이 댓글의 답글 목록 */
    @OneToMany(mappedBy = "parent",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<Comment> replies = new ArrayList<>();

    /* ─── 편의 생성자 ───────────────────────── */
    public Comment(String content, String author, Course course) {
        this(content, author, course, null);
    }

    public Comment(String content, String author, Course course, Comment parent) {
        this.content = content;
        this.author  = author;
        this.course  = course;
        this.parent  = parent;
    }
}