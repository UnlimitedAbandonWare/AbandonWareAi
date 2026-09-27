package com.example.lms.api;

import com.example.lms.api.dto.ChatSessionDto;
import com.example.lms.api.dto.CreateSessionRequest;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatHistoryServiceImpl;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;




@RestController
@RequestMapping("/api/chat-extra")
public class ChatApiControllerExtra {

    private final ChatSessionRepository sessionRepository;
    private final ClientOwnerKeyResolver ownerKeyResolver;
    private final ChatHistoryServiceImpl chatHistoryService;

    public ChatApiControllerExtra(ChatSessionRepository sessionRepository,
                                  ClientOwnerKeyResolver ownerKeyResolver,
                                  ChatHistoryServiceImpl chatHistoryService) {
        this.sessionRepository = sessionRepository;
        this.ownerKeyResolver = ownerKeyResolver;
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSessionDto>> listSessions() {
        String ownerKey = ownerKeyResolver.ownerKey();
        if (ownerKey == null || ownerKey.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        List<ChatSessionDto> list = sessionRepository
                .findByOwnerKeyOrderByCreatedAtDesc(ownerKey)
                .stream()
                .map(ChatSessionDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/sessions")
    public ResponseEntity<ChatSessionDto> startSession(@RequestBody CreateSessionRequest req) {
        String ownerKey = ownerKeyResolver.ownerKey();
        if (ownerKey == null || ownerKey.isBlank()) {
            // ownerKey cookie is issued by OwnerKeyBootstrapFilter (/bootstrap), return 400 if absent
            return ResponseEntity.badRequest().build();
        }
        String title = (req != null && req.getTitle() != null && !req.getTitle().isBlank())
                ? req.getTitle() : "New Session";
        ChatSession session = chatHistoryService.createEmptyAnonymousSession(title, ownerKey);
        return ResponseEntity.ok(ChatSessionDto.from(session));
    }

    @ExceptionHandler(ChatHistoryService.SessionQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> sessionQuotaExceeded(
            ChatHistoryService.SessionQuotaExceededException ignored) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", 400, "reasonCode", "session_quota_exceeded"));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<ChatSessionDto> getSession(@PathVariable Long id) {
        String ownerKey = ownerKeyResolver.ownerKey();
        return sessionRepository.findById(id)
                .filter(s -> ownerKey != null && ownerKey.equals(s.getOwnerKey()))
                .map(ChatSessionDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).build());
    }

}
