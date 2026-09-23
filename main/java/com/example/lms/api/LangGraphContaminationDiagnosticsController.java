package com.example.lms.api;

import com.example.lms.service.rag.langgraph.LangGraphContaminationReplayService;
import com.example.lms.service.rag.langgraph.LangGraphContaminationReport;
import com.example.lms.service.rag.langgraph.LangGraphContaminationReportStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/langgraph-contamination")
public class LangGraphContaminationDiagnosticsController {

    private final LangGraphContaminationReplayService replayService;
    private final LangGraphContaminationReportStore reportStore;

    public LangGraphContaminationDiagnosticsController(LangGraphContaminationReplayService replayService,
                                                       LangGraphContaminationReportStore reportStore) {
        this.replayService = replayService;
        this.reportStore = reportStore;
    }

    @PostMapping(value = "/replay", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public LangGraphContaminationReport replay(
            @RequestBody(required = false) LangGraphContaminationReplayService.ReplayRequest request) {
        return replayService.replay(request);
    }

    @GetMapping(value = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> reports(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", Instant.now().toString());
        out.put("reports", reportStore.summaries(limit));
        return out;
    }

    @GetMapping(value = "/reports/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> report(@PathVariable("runId") String runId) {
        return reportStore.get(runId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "report not found")));
    }
}
