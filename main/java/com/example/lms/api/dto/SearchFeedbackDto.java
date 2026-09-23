package com.example.lms.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;



/**
 * DTO representing search feedback submitted by the client.  When a user
 * dislikes a search result the front-end posts this payload to the
  * /api/search/feedback endpoint.  The server then records the downvote
 * into a session-scoped blocklist so that subsequent searches can filter
 * unwanted results.
 */
@Data
public class SearchFeedbackDto {
    @NotBlank
    private String sessionId;
    @NotBlank
    private String query;
    @NotBlank
    private String url;
    private String title;
    private String host;
    private String reason;
    /**
     * The action to perform.  When equal to "downvote" the feedback will
     * register a dislike.  Other values are ignored.
     */
    private String action;
}