package com.example.lms.api;

import com.example.lms.dto.FeedbackDto;
import com.example.lms.service.MemoryReinforcementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class FeedbackController {
    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final MemoryReinforcementService memoryService;

    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(@RequestBody FeedbackDto req) {
        try {
            boolean positive = "POSITIVE".equalsIgnoreCase(req.rating());
            memoryService.applyFeedback(
                    String.valueOf(req.sessionId()),
                    req.message(),
                    positive,
                    req.corrected()
            );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("feedback error", e);
            return ResponseEntity.badRequest().body("feedback error: " + e.getMessage());
        }
    }
}