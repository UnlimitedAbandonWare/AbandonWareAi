// src/main/java/com/example/lms/repository/CommentRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;




public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 강의별 최상위 댓글(부모가 없는 것들) */
    List<Comment> findByCourseIdAndParentIsNullOrderByCreatedAtDesc(Long courseId);

    /** 특정 댓글의 모든 답글 */
    List<Comment> findByParentIdOrderByCreatedAtAsc(Long parentId);
}