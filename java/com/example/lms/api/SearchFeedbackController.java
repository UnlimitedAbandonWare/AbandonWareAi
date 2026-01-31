package com.example.lms.api;

import com.example.lms.api.dto.SearchFeedbackDto;
import com.example.lms.service.rag.feedback.FeedbackBlocklistRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;



/**
 * REST endpoint for recording user feedback on search results.  When the
 * front-end submits a downvote action this controller stores the
 * associated host, title and reason into the session blocklist via the
 * {@link FeedbackBlocklistRegistry}.  Only downvote actions are
 * recognised; other actions result in a bad request response.
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchFeedbackController {
    private final FeedbackBlocklistRegistry registry;

    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(@RequestBody @Valid SearchFeedbackDto dto) {
        // Only handle explicit downvote actions
        if (!"downvote".equalsIgnoreCase(dto.getAction())) {
            return ResponseEntity.badRequest().build();
        }
        registry.downvote(dto.getSessionId(), dto.getHost(), dto.getTitle(), dto.getUrl(), dto.getReason());
        return ResponseEntity.ok().build();
    }
}