// src/main/java/com/example/lms/web/CourseController.java
package com.example.lms.web;

import com.example.lms.domain.Course;
import com.example.lms.domain.Professor;
import com.example.lms.service.CommentService;
import com.example.lms.service.CourseService;
import com.example.lms.service.ProfessorService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * 강의(Course) CRUD + 교수 드롭다운/필터 + 댓글/답글 기능
 */
@Controller
@RequestMapping("/courses")
public class CourseController {

    private final CourseService    courseService;
    private final ProfessorService professorService;
    private final CommentService   commentService;

    public CourseController(CourseService courseService,
                            ProfessorService professorService,
                            CommentService commentService) {
        this.courseService    = courseService;
        this.professorService = professorService;
        this.commentService   = commentService;
    }

    /* (1) 강의 목록  ─ 교수별 필터 지원 */
    @GetMapping
    public String list(@RequestParam(value = "profId", required = false) Long profId,
                       Model model) {
        List<Course> courses = (profId == null)
                ? courseService.findAll()
                : professorService.findById(profId).getCourses();

        model.addAttribute("courses", courses);
        model.addAttribute("allProfessors", professorService.findAll());
        model.addAttribute("profId", profId);
        return "courses/list";
    }

    /* (2) 강의 등록 폼 (빈 객체도 빌더로!) */
    @GetMapping("/new")
    public String createForm(@RequestParam(value = "profId", required = false) Long profId,
                             Model model) {
        model.addAttribute("course", Course.builder().build());
        model.addAttribute("allProfessors", professorService.findAll());
        model.addAttribute("selectedProfId", profId);
        return "courses/form";
    }

    /* (3) 강의 등록 처리 (빌더 사용 예제) */
    @PostMapping("/new")
    public String create(@RequestParam String title,
                         @RequestParam("description") String description,
                         Authentication auth) {
        Professor p = professorService.findByUsername(auth.getName());
        Course c = Course.builder()
                .title(title)
                .description(description)  // ← 여기서 description 변수 사용
                .professor(p)
                .build();
        courseService.save(c);
        return "redirect:/courses?profId=" + p.getId();
    }


    /* (4) 강의 수정 폼 */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Course course = courseService.findById(id);
        model.addAttribute("course", course);
        model.addAttribute("allProfessors", professorService.findAll());
        model.addAttribute("selectedProfId", course.getProfessor().getId());
        return "courses/form";
    }

    /* (5) 강의 수정 처리 */
    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) Long professorId,
                         @ModelAttribute Course course) {
        courseService.updateCourse(id, course.getTitle(), course.getDescription(), professorId);
        return "redirect:/courses" + ((professorId != null) ? "?profId=" + professorId : "");
    }

    /* (6) 강의 삭제 */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) Long profId) {
        courseService.deleteCourse(id);
        return "redirect:/courses" + ((profId != null) ? "?profId=" + profId : "");
    }

    /* (7) 강의 상세 + 댓글/답글 목록 */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("course", courseService.findById(id));
        model.addAttribute("comments", commentService.findTopLevel(id));
        return "courses/detail";
    }

    /* (8) 댓글 또는 답글 등록 */
    @PostMapping("/{id}/comments")
    public String addComment(@PathVariable Long id,
                             @RequestParam String author,
                             @RequestParam String content,
                             @RequestParam(required = false) Long parentId,
                             RedirectAttributes rttr) {
        commentService.addComment(id, author, content, parentId);
        rttr.addFlashAttribute("msg", "댓글이 등록되었습니다.");
        return "redirect:/courses/" + id;
    }

    /* (9) 댓글/답글 삭제 */
    @PostMapping("/{courseId}/comments/{commentId}/delete")
    public String deleteComment(@PathVariable Long courseId,
                                @PathVariable Long commentId) {
        commentService.delete(commentId);
        return "redirect:/courses/" + courseId;
    }

    // ※ controller 내에 courseRepo 나 save 메서드는 모두 제거되었습니다.
}
