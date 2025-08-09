
// src/main/java/com/example/lms/web/NoticeController.java
package com.example.lms.web;

import com.example.lms.domain.NoticeType;
import com.example.lms.service.NoticeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/notices")
public class NoticeController {
    private final NoticeService service;
    public NoticeController(NoticeService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(required=false) NoticeType type,
                       @RequestParam(required=false) Long targetId,
                       Model model) {
        List<com.example.lms.domain.Notice> list;
        if (type != null) {
            list = service.findByTypeAndTarget(type, targetId);
        } else {
            list = service.findAll();
        }
        model.addAttribute("notices", list);
        model.addAttribute("types", NoticeType.values());
        return "notices/list";
    }
}
