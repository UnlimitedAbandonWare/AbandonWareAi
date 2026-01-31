
package com.example.lms.probe;

import com.example.lms.service.nova.ExpertOpenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/probe/expert-open")
@ConditionalOnProperty(prefix="probe.search", name="enabled", havingValue="true", matchIfMissing=false)
public class ExpertOpenProbeController {

    @Autowired(required=false)
    private ExpertOpenService expertOpen;

    @PostMapping
    public ResponseEntity<?> open(@RequestBody(required=false) Map<String, Object> body) {
        if (expertOpen == null) {
            return ResponseEntity.ok(Map.of("ok", false, "enabled", false, "reason", "nova.expert-open.enabled=false"));
        }
        List<String> urls = Optional.ofNullable(body).map(m -> (List<String>) m.get("urls")).orElse(List.of());
        try {
            var pages = expertOpen.fetchAll(urls).join();
            var result = pages.stream().map(p -> Map.of("url", p.url(), "status", p.status(), "length", p.body()!=null?p.body().length():0)).collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("ok", true, "enabled", true, "count", result.size(), "result", result));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", e.getMessage()));
        }
    }
}
