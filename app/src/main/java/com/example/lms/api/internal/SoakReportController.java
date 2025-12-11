package com.example.lms.api.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping(path = "/internal/soak")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.api.internal.SoakReportController
 * Role: controller
 * Key Endpoints: GET /internal/soak/report, ANY /internal/soak/internal/soak
 * Dependencies: com.fasterxml.jackson.databind.ObjectMapper
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.api.internal.SoakReportController
role: controller
api:
  - GET /internal/soak/report
  - ANY /internal/soak/internal/soak
*/
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakReportController {

    @GetMapping(path = "/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> report(@RequestParam(name = "since", required = false) String sinceTs) throws IOException {
        Map<String,Object> res = new LinkedHashMap<>();
        List<Map<String,Object>> files = new ArrayList<>();
        Path dir = Paths.get("artifacts/soak");
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
                for (Path p : ds) {
                    Map<String,Object> m = new ObjectMapper().readValue(p.toFile(), Map.class);
                    files.add(m);
                }
            }
        }
        res.put("count", files.size());
        res.put("items", files);
        return res;
    }
}