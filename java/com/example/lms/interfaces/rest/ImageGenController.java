package com.example.lms.interfaces.rest;

import com.example.lms.dto.ImageTask;
import com.example.lms.gptapi.images.ImageGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ImageGenController {

    private final ImageGenerationService imageGenerationService;

    @PostMapping(value = "/imagine", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, String>> imagine(@RequestBody Map<String, String> body) {
        String prompt = (body == null ? null : body.get("prompt"));
        if (prompt == null || prompt.trim().isEmpty()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "prompt required");
            return ResponseEntity.badRequest().body(error);
        }
        ImageTask task = ImageTask.builder()
                .mode("GENERATE")
                .prompt(prompt.trim())
                .build();
        String url = imageGenerationService.handleImageTask(task);
        Map<String, String> res = new HashMap<>();
        if (url != null && !url.isBlank()) {
            res.put("url", url);
        } else {
            res.put("error", "generation failed");
        }
        return ResponseEntity.ok(res);
    }
}
