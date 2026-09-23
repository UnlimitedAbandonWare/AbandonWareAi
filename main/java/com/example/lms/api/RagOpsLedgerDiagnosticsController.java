package com.example.lms.api;

import com.example.lms.service.ops.RagOpsLedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/rag/ops-ledger")
public class RagOpsLedgerDiagnosticsController {

    private final RagOpsLedgerService ledgerService;

    public RagOpsLedgerDiagnosticsController(RagOpsLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(ledgerService.summary(hours));
    }

    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> recent(@RequestParam(required = false) String entryType,
                                                      @RequestParam(required = false) String decision,
                                                      @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ledgerService.isEnabled());
        out.put("items", ledgerService.recent(entryType, decision, limit));
        return ResponseEntity.ok(out);
    }
}
