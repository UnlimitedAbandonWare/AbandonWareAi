package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.jobs.JobService;
import com.example.lms.integrations.n8n.N8nNotifier;
import com.example.lms.service.ChatService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * Thin REST facade exposing simplified task APIs for synchronous and
 * asynchronous question answering. These endpoints are intended for
 * consumption by external orchestrators such as n8n and rely on the
 * underlying {@link ChatService} for the heavy lifting. Job state is
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper jobMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ChatGenerationAdmissionFilter costs;
    private void checkNewWorkCost() { if (costs != null) costs.costCheckCurrentRequest().run(); }

    @jakarta.annotation.PostConstruct
    void registerPersistedWork() {
        jobService.registerHandler("task_ask", new JobService.JobHandler() {
            public String execute(String json) throws Exception {
                TaskAskRequest req = jobMapper.readValue(json, TaskAskRequest.class);
                ChatRequestDto chatReq = toChatRequest(req);
                var result = chatService.continueChat(chatReq);
                return jobMapper.writeValueAsString(new ChatResponseDto(result.content(), chatReq.getSessionId(), result.modelUsed(), result.ragUsed()));
            }
            public boolean needsCompletion(String json) throws Exception {
                String url = jobMapper.readValue(json, TaskAskRequest.class).callbackUrl();
                return url != null && !url.isBlank();
            }
            public boolean completed(String id, String request, String result) throws Exception {
                return notifier.notifyAcknowledged(jobMapper.readValue(request, TaskAskRequest.class).callbackUrl(),
                        callbackPayload(id, jobMapper.readValue(result, ChatResponseDto.class)));
            }
        });
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> task(@PathVariable String taskId) {
        return jobService.find(taskId, currentOwner()).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{taskId}/result", produces = "application/json")
    public ResponseEntity<?> result(@PathVariable String taskId) {
        String owner = currentOwner();
        var snapshot = jobService.find(taskId, owner);
        if (snapshot.isEmpty()) return ResponseEntity.notFound().build();
        return jobService.result(taskId, owner).<ResponseEntity<?>>map(body -> ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "result_not_ready", "state", snapshot.get().state())));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable String taskId) {
        String owner = currentOwner();
        if (jobService.find(taskId, owner).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("cancelRequested", jobService.cancel(taskId, owner)));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> jobStoreUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "job_store_unavailable"));
    }

    private static String currentOwner() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                authentication == null ? "system-job" : authentication.getName());
    }

    /**
     * Handle a synchronous ask request. The message is delegated to
     * {@link ChatService#continueChat(ChatRequestDto)} and the response
     * returned directly. Errors result in a 500 status with a generic
     * message and no sensitive details.
     *
     * @param req the task request payload
     * @return the assistant response or an error
     */
    @PostMapping("/ask")
    public ResponseEntity<ChatResponseDto> askSync(@RequestBody TaskAskRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponseDto("bad_request", null, null, false));
        }
        if (missingMessage(req)) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponseDto("bad_request", null, "missing_message", false));
        }
        checkNewWorkCost();
        try {
            ChatRequestDto chatReq = toChatRequest(req);
            var result = chatService.continueChat(chatReq);
            ChatResponseDto dto = new ChatResponseDto(
                    result.content(),
                    chatReq.getSessionId(),
                    result.modelUsed(),
                    result.ragUsed());
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            if (result.modelUsed() != null && !result.modelUsed().isBlank()) {
                ok.header("X-Model-Used", result.modelUsed());
            }
            if (result.ragUsed()) {
                ok.header("X-RAG-Used", "true");
            }
            return ok.body(dto);
        } catch (Exception e) {
            log.warn("[TasksApi] askSync failed errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)),
                    messageLength(e));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ChatResponseDto("정보 없음", null, null, false));
        }
    }

    /**
     * Submit an asynchronous ask request. The request is recorded in the
     * job store and executed on a background thread. When complete the
     * supplied callback URL is invoked with the task result. A 202
     * response containing the task identifier is returned immediately.
     *
     * @param req the task request
     * @return an accepted response containing the new task identifier
     */
    public ResponseEntity<Map<String, String>> askAsync(TaskAskRequest req) {return askAsync(req,null);}
    @PostMapping("/ask/async")
    public ResponseEntity<Map<String, String>> askAsync(@RequestBody TaskAskRequest req,@RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey) {
        if (req == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "code", "missing_request"));
        }
        if (missingMessage(req)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "code", "missing_message"));
        }
        if(idempotencyKey!=null){
            if(!idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}"))return ResponseEntity.badRequest().body(Map.of("error","invalid_idempotency_key"));
            var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if(auth==null||!auth.isAuthenticated()||auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)return ResponseEntity.status(401).body(Map.of("error","authentication_required"));
            try{
                String owner=currentOwner(),fingerprint=taskFingerprint(req);
                var existing=jobService.findAdmission("task_ask",owner,idempotencyKey,fingerprint);
                var admission=existing.orElseGet(()->{checkNewWorkCost();return jobService.enqueueOnce("task_ask",req,Map.of("ownerHash",owner),req.sid()==null?null:req.sid().toString(),idempotencyKey,fingerprint);});
                boolean active=java.util.Set.of("PENDING","RUNNING","CANCEL_REQUESTED").contains(admission.state());
                return ResponseEntity.status(active?202:200).cacheControl(org.springframework.http.CacheControl.noStore()).location(java.net.URI.create("/v1/tasks/"+admission.taskId()))
                        .body(Map.of("taskId",admission.taskId(),"state",admission.state(),"replayed",Boolean.toString(admission.replayed()),"resultUrl","/v1/tasks/"+admission.taskId()+"/result"));
            }catch(JobService.IdempotencyConflict conflict){return ResponseEntity.status(409).body(Map.of("error","idempotency_conflict"));}
            catch(UnsupportedOperationException unsupported){return ResponseEntity.status(503).body(Map.of("error","durable_idempotency_required"));}
        }
        checkNewWorkCost();
        String jobId = jobService.enqueue(
                "task_ask",
                req,
                Map.of("ownerHash", currentOwner()),
                (req.sid() == null ? null : String.valueOf(req.sid())));
        if (jobId == null || jobId.isBlank()) {
            traceAsyncJobEnqueueFailed(req);
            log.warn("[TasksApi] async enqueue returned empty job id");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "job_enqueue_failed"));
        }
        if (jobService.runsPersistedJobs()) {
            return ResponseEntity.accepted().location(java.net.URI.create("/v1/tasks/" + jobId)).body(Map.of("taskId", jobId));
        }
        // Explicit development implementations retain the legacy execution contract.
        jobService.executeAsync(jobId, () -> {
            ChatRequestDto chatReq = toChatRequest(req);
            var result = chatService.continueChat(chatReq);
            return new ChatResponseDto(
                    result.content(),
                    chatReq.getSessionId(),
                    result.modelUsed(),
                    result.ragUsed());
        }, res -> {
            // If a callback was provided notify the remote URL
            if (req.callbackUrl() != null && !req.callbackUrl().isBlank()) {
                try {
                    notifier.notify(req.callbackUrl(), callbackPayload(jobId, res));
                } catch (Exception ex) {
                    log.warn("[TasksApi] callback notification failed errorHash={} errorLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(messageOf(ex)),
                            messageLength(ex));
                }
            }
        });
        return ResponseEntity.accepted().body(Map.of("taskId", jobId));
    }
    private String taskFingerprint(TaskAskRequest req){
        // Preserve meaningful interior whitespace, nullable automatic flags and callback semantics.
        // Only NFC and CRLF/LF normalize text; ignored legacy history is not an execution input.
        Map<String,Object> fields=new java.util.TreeMap<>();
        fields.put("message",java.text.Normalizer.normalize(req.message().replace("\r\n","\n"),java.text.Normalizer.Form.NFC));
        fields.put("useRag",req.useRag());fields.put("useWebSearch",req.useWebSearch());fields.put("sid",req.sid());fields.put("model",req.model());fields.put("callbackUrl",req.callbackUrl());
        try{return org.apache.commons.codec.digest.DigestUtils.sha256Hex(jobMapper.writeValueAsBytes(fields));}catch(Exception invalid){throw new IllegalArgumentException("invalid_task_request");}
    }

    private static Map<String, Object> callbackPayload(String jobId, ChatResponseDto res) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", safeText(jobId));
        payload.put("content", res == null ? "" : safeText(res.getContent()));
        payload.put("modelUsed", res == null ? "" : safeText(res.getModelUsed()));
        payload.put("ragUsed", res != null && res.isRagUsed());
        return payload;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }

    private static void traceAsyncJobEnqueueFailed(TaskAskRequest req) {
        TraceStore.put("api.tasks.async.jobEnqueueFailed", true);
        TraceStore.inc("api.tasks.async.jobEnqueueFailed.count");
        TraceStore.put("api.tasks.async.skipped.reason", "job_enqueue_failed");
        TraceStore.put("api.tasks.async.messageLength",
                req == null || req.message() == null ? 0 : req.message().length());
        TraceStore.put("api.tasks.async.sessionHash",
                req == null || req.sid() == null ? "" : SafeRedactor.hashValue(String.valueOf(req.sid())));
        TraceStore.put("api.tasks.async.hasCallback",
                req != null && req.callbackUrl() != null && !req.callbackUrl().isBlank());
    }

    private static boolean missingMessage(TaskAskRequest req) {
        return req.message() == null || req.message().isBlank();
    }

    /**
     * Convert the lightweight task ask request into the richer ChatRequestDto
     * used by the core chat service. Only a subset of fields are mapped;
     * missing values fall back to the defaults defined by the DTO builder.
     *
     * @param req the task request
     * @return a new ChatRequestDto
     */
    private ChatRequestDto toChatRequest(TaskAskRequest req) {
        ChatRequestDto.Builder builder = ChatRequestDto.builder()
                .message(req.message())
                .model(req.model() != null ? req.model() : null)
                .sessionId(req.sid());
        // Use wrapper types for booleans to allow null (unspecified) values
        if (req.useRag() != null)
            builder.useRag(req.useRag());
        if (req.useWebSearch() != null)
            builder.useWebSearch(req.useWebSearch());
        return builder.build();
    }

    /**
     * Request body for the /v1/tasks/ask endpoints. This record captures
     * only the minimal inputs required by the tasks API. Additional
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
            String callbackUrl) {
    }
}
