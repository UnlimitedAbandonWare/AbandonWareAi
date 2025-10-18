package com.example.lms.api;

import com.example.lms.api.dto.ChatSessionDto;
import com.example.lms.api.dto.CreateSessionRequest;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.ChatSessionRepository;
import com.example.lms.web.ClientOwnerKeyResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;




@RestController
@RequestMapping("/api/chat-extra")
public class ChatApiControllerExtra {

    private final ChatSessionRepository sessionRepository;
    private final ClientOwnerKeyResolver ownerKeyResolver;

    public ChatApiControllerExtra(ChatSessionRepository sessionRepository,
                                  ClientOwnerKeyResolver ownerKeyResolver) {
        this.sessionRepository = sessionRepository;
        this.ownerKeyResolver = ownerKeyResolver;
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
        ChatSession session = new ChatSession(title, ownerKey, "ANON");
        session = sessionRepository.save(session);
        return ResponseEntity.ok(ChatSessionDto.from(session));
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