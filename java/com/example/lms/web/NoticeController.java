package com.example.lms.web;

import com.example.lms.domain.Notice;
import com.example.lms.domain.NoticeType;
import com.example.lms.service.NoticeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;




/**
 * 공지사항 웹 요청을 처리하는 컨트롤러
 * - 일반 사용자 목록/상세 보기
 * - 관리자인 경우 목록, 작성, 상세, 삭제 기능 제공
 */
@Controller
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    // ----------------- Public Endpoints -----------------

    /**
     * (1) 공공 공지 목록 (타입/대상별 필터 지원)
     */
    @GetMapping("/notices")
    public String listPublic(
            @RequestParam(required = false) NoticeType type,
            @RequestParam(required = false) Long targetId,
            Model model) {
        List<Notice> list = (type != null)
                ? noticeService.findByTypeAndTarget(type, targetId)
                : noticeService.findAll();
        model.addAttribute("notices", list);
        model.addAttribute("types", NoticeType.values());
        // also supply empty notice so header fragment's th:field can bind without error
        model.addAttribute("notice", new Notice());
        return "notices/list";
    }

    /**
     * (Optional) 공공 공지 상세 보기
     */
    @GetMapping("/notices/{id}")
    public String detailPublic(@PathVariable Long id, Model model) {
        Notice notice = noticeService.findAll().stream()
                .filter(n -> n.getId().equals(id))
                .findFirst()
                .orElseThrow();
        model.addAttribute("notice", notice);
        // supply empty notice for header binding
        model.addAttribute("searchNotice", new Notice());
        return "notices/detail";
    }

    // ----------------- Admin Endpoints -----------------

    /**
     * (2) 관리자용 공지 목록
     */
    @GetMapping("/admin/notices")
    public String listAdmin(Model model) {
        List<Notice> list = noticeService.findAll();
        model.addAttribute("notices", list);
        // ← add this so header fragment has a notice object to bind against
        model.addAttribute("notice", new Notice());
        return "admin/notices/list";
    }

    /**
     * (3) 새 공지 작성 폼 (관리자)
     */
    @GetMapping("/admin/notices/new")
    public String createFormAdmin(Model model) {
        model.addAttribute("notice", new Notice());
        model.addAttribute("types", NoticeType.values());
        return "admin/notices/form";
    }

    /**
     * (4) 새 공지 저장 (관리자)
     */
    @PostMapping("/admin/notices/new")
    public String createAdmin(
            @ModelAttribute Notice notice,
            RedirectAttributes rttr) {
        noticeService.postWithType(
                notice.getTitle(),
                notice.getContent(),
                notice.getType(),
                notice.getTargetDeptId(),
                notice.getTargetUserId());
        rttr.addFlashAttribute("msg", "공지 등록 완료");
        return "redirect:/admin/notices";
    }

    /**
     * (5) 개별 공지 상세 (관리자)
     */
    @GetMapping("/admin/notices/{id}")
    public String detailAdmin(@PathVariable Long id, Model model) {
        Notice notice = noticeService.findAll().stream()
                .filter(n -> n.getId().equals(id))
                .findFirst()
                .orElseThrow();
        model.addAttribute("notice", notice);
        // supply empty notice for any th:field in header fragment
        model.addAttribute("headerNotice", new Notice());
        return "admin/notices/detail";
    }

    /**
     * (6) 공지 삭제 (관리자)
     */
    @PostMapping("/admin/notices/{id}/delete")
    public String deleteAdmin(@PathVariable Long id,
                              RedirectAttributes rttr) {
        noticeService.delete(id);
        rttr.addFlashAttribute("msg", "공지 삭제 완료");
        return "redirect:/admin/notices";
    }
}