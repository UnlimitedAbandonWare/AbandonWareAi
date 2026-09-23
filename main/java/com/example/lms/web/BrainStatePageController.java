package com.example.lms.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class BrainStatePageController {

    @GetMapping("/brain-state")
    public String page() {
        return "brain-state";
    }
}
