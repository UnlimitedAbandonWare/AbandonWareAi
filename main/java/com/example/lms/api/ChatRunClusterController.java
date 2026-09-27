package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.chat.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/** Authenticated internal transport only; no public owner claim and no generation endpoint. */
@RestController
@RequestMapping("/api/chat/cluster")
@ConditionalOnProperty(name="chat.cluster.enabled",havingValue="true")
public final class ChatRunClusterController {
    private final ChatRunCluster cluster;
    private final ChatRunRegistry registry;
    private final ChatHistoryService history;
    public ChatRunClusterController(ChatRunCluster cluster, ChatRunRegistry registry, ChatHistoryService history) {
        this.cluster=cluster;this.registry=registry;this.history=history;
    }
    @PostMapping(value="/attach",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> attach(@RequestBody ChatRunCluster.Command command,@RequestHeader HttpHeaders headers) {
        cluster.authorizePeer("attach",command,headers);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(registry.attachInteractiveExact(command.sessionId(),command.runToken())
                .orElseThrow(ChatRunOwnerDirectory::unavailable));
    }
    @PostMapping("/state")
    public ResponseEntity<ChatRunRegistry.RunView> state(@RequestBody ChatRunCluster.Command command,@RequestHeader HttpHeaders headers) {
        cluster.authorizePeer("state",command,headers);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(registry.describeExact(command.sessionId(),command.runToken())
                .orElseThrow(ChatRunOwnerDirectory::unavailable));
    }
    @PostMapping({"/cancel","/ready","/final","/recovery","/delete"})
    public ResponseEntity<ChatRunCluster.Accepted> control(@RequestBody ChatRunCluster.Command command,@RequestHeader HttpHeaders headers,
                                                        jakarta.servlet.http.HttpServletRequest request) {
        String action=request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1);
        cluster.authorizePeer(action,command,headers);
        boolean accepted=switch(action) {
            case "delete" -> { registry.cancelSessionForDeletion(command.sessionId()); yield true; }
            case "cancel" -> registry.cancelExact(command.sessionId(),command.runToken(),()->{
                try { history.appendMessage(command.sessionId(),"assistant","Response stopped"); }
                catch(RuntimeException ignored){ /* Same best-effort marker as the public cancel boundary. */ }
            });
            case "ready" -> registry.acknowledgeExact(command.sessionId(),command.runToken());
            case "final" -> registry.acknowledgeFinalDeliveryExact(command.sessionId(),command.runToken());
            case "recovery" -> registry.acknowledgeRecoveredDeliveryExact(command.sessionId(),command.runToken());
            default -> false;
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ChatRunCluster.Accepted(accepted));
    }
}
