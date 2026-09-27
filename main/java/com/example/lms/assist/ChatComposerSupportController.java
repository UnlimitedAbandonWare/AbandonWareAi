package com.example.lms.assist;

import com.example.lms.api.AttachmentController;
import com.example.lms.dto.AttachmentDto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 공개 /chat 컴포저(첨부·음성)의 얇은 어댑터.
 * 인가 변경 없이 이미 열린 /api/chat/** 아래에서 기존 빈에 위임한다.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatComposerSupportController {

    private static final Duration TRANSCRIBE_TIMEOUT = Duration.ofSeconds(40);
    private static final ClassPathResource PCM_WORKLET =
            new ClassPathResource("static/conversate/pcm-worklet.js");

    private final AttachmentController attachments;
    private final ObjectProvider<ConversateCloudStt> cloudStt;

    public ChatComposerSupportController(AttachmentController attachments,
                                         ObjectProvider<ConversateCloudStt> cloudStt) {
        this.attachments = attachments;
        this.cloudStt = cloudStt;
    }

    /**
     * /api/attachments/upload 와 동일한 핸들러에 위임하는 별칭.
     * 그 경로는 기본 체인 authenticated() 라 익명 /chat 에서 403 이다.
     */
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<AttachmentDto> uploadAttachment(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            Authentication authentication) {
        return attachments.upload(files, sessionId, authentication);
    }

    /** 기존 워크렛 파일을 그대로 서빙 — 익명이 도달 가능한 /api/chat/** 경로. */
    @GetMapping(value = "/pcm-worklet.js", produces = "text/javascript")
    public ResponseEntity<ClassPathResource> pcmWorklet() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType("text/javascript"))
                .body(PCM_WORKLET);
    }

    /**
     * 완결 발화 PCM(Int16 16kHz mono)을 ConversateCloudStt.transcribe 에 위임.
     * {transcript} 는 컴포저에 삽입되어 사용자가 수정 후 전송한다.
     */
    @PostMapping(value = "/transcribe",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> transcribe(@RequestBody(required = false) byte[] pcm) {
        ConversateCloudStt stt = cloudStt.getIfAvailable();
        if (stt == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "asr_disabled");
        }
        String transcript;
        try {
            transcript = stt.transcribe(pcm).block(TRANSCRIBE_TIMEOUT);
        } catch (RuntimeException failure) {
            String reason = reason(failure);
            HttpStatus status = "stt_audio_limit".equals(reason)
                    ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE;
            throw new ResponseStatusException(status, reason);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("transcript", transcript == null ? "" : transcript));
    }

    /** 고정 reason 문자열만 노출한다(stt_*, ASR_*, groq_speech_http_*). */
    private static String reason(Throwable failure) {
        String message = Exceptions.unwrap(failure).getMessage();
        if (message == null || !message.matches("[A-Za-z0-9_.-]{1,64}")) {
            return "stt_failed";
        }
        return message;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .headers(error.getHeaders())
                .cacheControl(CacheControl.noStore())
                .body(Map.of("reason",
                        error.getReason() == null ? "chat_composer_failed" : error.getReason()));
    }
}
