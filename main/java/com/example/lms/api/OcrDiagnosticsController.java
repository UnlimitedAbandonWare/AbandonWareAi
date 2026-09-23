package com.example.lms.api;

import com.example.lms.service.AttachmentInspectionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics")
public class OcrDiagnosticsController {
    private final AttachmentInspectionService attachmentInspectionService;

    public OcrDiagnosticsController(AttachmentInspectionService attachmentInspectionService) {
        this.attachmentInspectionService = attachmentInspectionService;
    }

    @GetMapping(value = "/ocr", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ocr() {
        return attachmentInspectionService.readiness();
    }
}
