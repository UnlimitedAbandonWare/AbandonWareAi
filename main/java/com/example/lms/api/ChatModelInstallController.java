package com.example.lms.api;

import com.example.lms.service.ModelInstallService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class ChatModelInstallController {
    private final ModelInstallService installs;

    public ChatModelInstallController(ModelInstallService installs) {
        this.installs = installs;
    }

    public record InstallRequest(String target, String model, String route) {}

    /** Thin web-install entry: local pull or cloud route registration.
     *  Metadata-only response; never returns credentials or model bytes. */
    @PostMapping("/api/chat/models/install")
    public ResponseEntity<Map<String, Object>> install(@RequestBody(required = false) InstallRequest request) {
        InstallRequest body = request == null ? new InstallRequest(null, null, null) : request;
        Map<String, Object> result = installs.install(body.target(), body.model(), body.route());
        HttpStatus status = switch (String.valueOf(result.get("status"))) {
            case "accepted" -> HttpStatus.ACCEPTED;
            case "already_running" -> HttpStatus.CONFLICT;
            case "installed", "registered", "already_present", "already_registered" -> HttpStatus.OK;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/api/chat/models/install/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(installs.status());
    }
}
