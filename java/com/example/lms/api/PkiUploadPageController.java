package com.example.lms.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;



// PkiUploadPageController.java
@Controller
public class PkiUploadPageController {

    // 바로 이 경로로 접속해야 합니다.
    @GetMapping("/pki-upload")
    public String showUploadPage() {
        return "pki-upload";
    }
}