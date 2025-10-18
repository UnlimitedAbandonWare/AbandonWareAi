
// src/main/java/com/example/lms/service/CommentService.java
package com.example.lms.service;

import com.example.lms.domain.Comment;
import com.example.lms.domain.Course;
import com.example.lms.repository.CommentRepository;
import com.example.lms.service.CourseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;




@Service
@Transactional
public class CommentService {

    private final CommentRepository commentRepo;
    private final CourseService courseService;

    public CommentService(CommentRepository commentRepo,
                          CourseService courseService) {
        this.commentRepo   = commentRepo;
        this.courseService = courseService;
    }

    /** 강의별 최상위 댓글 + 답글 목록 */
    @Transactional(readOnly = true)
    public List<Comment> findTopLevel(Long courseId) {
        return commentRepo.findByCourseIdAndParentIsNullOrderByCreatedAtDesc(courseId);
    }

    /** 댓글 작성 */
    public Comment addComment(Long courseId,
                              String author,
                              String content,
                              Long parentId) {

        Course course = courseService.findById(courseId);
        Comment parent = (parentId != null)
                ? commentRepo.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("부모 댓글이 존재하지 않습니다."))
                : null;

        Comment cmt = new Comment(content, author, course, parent);
        return commentRepo.save(cmt);
    }

    /** 댓글 삭제 */
    public void delete(Long commentId) {
        commentRepo.deleteById(commentId);
    }
}