package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.jobs.JobService;
import com.example.lms.integrations.n8n.N8nNotifier;
import com.example.lms.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Thin REST facade exposing simplified task APIs for synchronous and
 * asynchronous question answering.  These endpoints are intended for
 * consumption by external orchestrators such as n8n and rely on the
 * underlying {@link ChatService} for the heavy lifting.  Job state is
 * persisted in {@link JobService} to enable polling and callbacks.
 */
@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
public class TasksApiController {
    private static final Logger log = LoggerFactory.getLogger(TasksApiController.class);

    private final ChatService chatService;
    private final JobService jobService;
    private final N8nNotifier notifier;

    /**
     * Handle a synchronous ask request.  The message is delegated to
     * {@link ChatService#continueChat(ChatRequestDto)} and the response
     * returned directly.  Errors result in a 500 status with a generic
     * message and no sensitive details.
     *
     * @param req the task request payload
     * @return the assistant response or an error
     */
    @PostMapping("/ask")
    public ResponseEntity<ChatResponseDto> askSync(@RequestBody TaskAskRequest req) {
        try {
            ChatRequestDto chatReq = toChatRequest(req);
            var result = chatService.continueChat(chatReq);
            ChatResponseDto dto = new ChatResponseDto(
                    result.content(),
                    chatReq.getSessionId(),
                    result.modelUsed(),
                    result.ragUsed()
            );
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            if (result.modelUsed() != null && !result.modelUsed().isBlank()) {
                ok.header("X-Model-Used", result.modelUsed());
            }
            if (result.ragUsed()) {
                ok.header("X-RAG-Used", "true");
            }
            return ok.body(dto);
        } catch (Exception e) {
            log.warn("[TasksApi] askSync failed: {}", e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ChatResponseDto("정보 없음", null, null, false));
        }
    }

    /**
     * Submit an asynchronous ask request.  The request is recorded in the
     * job store and executed on a background thread.  When complete the
     * supplied callback URL is invoked with the task result.  A 202
     * response containing the task identifier is returned immediately.
     *
     * @param req the task request
     * @return an accepted response containing the new task identifier
     */
    @PostMapping("/ask/async")
    public ResponseEntity<Map<String, String>> askAsync(@RequestBody TaskAskRequest req) {
        String jobId = jobService.enqueue(
                "task_ask",
                req,
                null,
                (req.sid() == null ? null : String.valueOf(req.sid()))
        );
        // Launch background execution
        jobService.executeAsync(jobId, () -> {
            ChatRequestDto chatReq = toChatRequest(req);
            var result = chatService.continueChat(chatReq);
            return new ChatResponseDto(
                    result.content(),
                    chatReq.getSessionId(),
                    result.modelUsed(),
                    result.ragUsed()
            );
        }, res -> {
            // If a callback was provided notify the remote URL
            if (req.callbackUrl() != null && !req.callbackUrl().isBlank()) {
                try {
                    notifier.notify(req.callbackUrl(), Map.of(
                            "taskId", jobId,
                            "content", res.getContent(),
                            "modelUsed", res.getModelUsed(),
                            "ragUsed", res.isRagUsed()
                    ));
                } catch (Exception ex) {
                    log.warn("[TasksApi] callback notification failed: {}", ex.toString());
                }
            }
        });
        return ResponseEntity.accepted().body(Map.of("taskId", jobId));
    }

    /**
     * Convert the lightweight task ask request into the richer ChatRequestDto
     * used by the core chat service.  Only a subset of fields are mapped;
     * missing values fall back to the defaults defined by the DTO builder.
     *
     * @param req the task request
     * @return a new ChatRequestDto
     */
    private ChatRequestDto toChatRequest(TaskAskRequest req) {
        ChatRequestDto.ChatRequestDtoBuilder builder = ChatRequestDto.builder()
                .message(req.message())
                .model(req.model() != null ? req.model() : null)
                .sessionId(req.sid());
        // Use wrapper types for booleans to allow null (unspecified) values
        if (req.useRag() != null) builder.useRag(req.useRag());
        if (req.useWebSearch() != null) builder.useWebSearch(req.useWebSearch());
        return builder.build();
    }

    /**
     * Request body for the /v1/tasks/ask endpoints.  This record captures
     * only the minimal inputs required by the tasks API.  Additional
     * options can be added in future revisions without impacting
     * compatibility.
     *
     * @param message      the user query
     * @param history      ignored for now; reserved for future use
     * @param useRag       whether retrieval should be used (nullable)
     * @param useWebSearch whether live web search should be used (nullable)
     * @param sid          optional session identifier
     * @param model        optional model identifier
     * @param callbackUrl  optional callback URL for async requests
     */
    public record TaskAskRequest(
            String message,
            java.util.List<Object> history,
            Boolean useRag,
            Boolean useWebSearch,
            Long sid,
            String model,
            String callbackUrl
    ) {}
}