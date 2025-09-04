package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DebugPageController {
    @GetMapping("/debug")
    public String debugPage() {
        return "debug";
    }
}
