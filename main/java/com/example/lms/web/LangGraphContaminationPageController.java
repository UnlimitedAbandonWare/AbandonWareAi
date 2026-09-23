package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class LangGraphContaminationPageController {

    @GetMapping("/langgraph-contamination")
    public String page() {
        return "langgraph-contamination";
    }
}
