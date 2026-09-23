package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Read-only admin page for RAG learning data verification.
 */
@Controller
@RequestMapping("/admin")
public class LearningDataPageController {

    @GetMapping("/learning-data")
    public String page() {
        return "learning-data";
    }
}
