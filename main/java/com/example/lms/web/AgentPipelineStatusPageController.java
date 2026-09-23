package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AgentPipelineStatusPageController {

    @GetMapping("/pipeline-status")
    public String page() {
        return "pipeline-status";
    }
}
