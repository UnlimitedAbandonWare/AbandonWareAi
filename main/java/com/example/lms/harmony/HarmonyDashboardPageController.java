package com.example.lms.harmony;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HarmonyDashboardPageController {

    @GetMapping("/harmony")
    public String dashboard() {
        return "harmony-dashboard";
    }
}
