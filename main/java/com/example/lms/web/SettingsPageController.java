package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SettingsPageController {
    @GetMapping("/settings")
    public String settings() { return "settings"; }
}
