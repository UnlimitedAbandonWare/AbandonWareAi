package com.example.lms.api.internal;

import com.example.lms.moe.RgbSoakReport;
import com.example.lms.scheduler.TrainingJobRunner;
import com.example.lms.service.soak.SoakQuickReport;
import com.example.lms.service.soak.SoakReport;
import com.example.lms.service.soak.SoakTestService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/soak")
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakApiController {
    private final SoakTestService service;
    private final ObjectProvider<TrainingJobRunner> autoEvolveRunner;

    public SoakApiController(SoakTestService service,
                             ObjectProvider<TrainingJobRunner> autoEvolveRunner) {
        this.service = service;
        this.autoEvolveRunner = autoEvolveRunner;
    }

    @GetMapping("/run")
    public ResponseEntity<SoakReport> run(@RequestParam(defaultValue = "10") int k,
                                          @RequestParam(defaultValue = "all") String topic) {
        return ResponseEntity.ok(service.run(k, topic));
    }

    /**
     * Quick soak endpoint with a fixed schema JSON response.
     *
     * Example:
     * /internal/soak/quick?topic=naver-fixed10&k=10
     *
     * Backward compatibility:
     * - topic=naver-bing-fixed10 is treated as an alias of naver-fixed10.
     */
    @GetMapping("/quick")
    public ResponseEntity<SoakQuickReport> quick(@RequestParam(defaultValue = "10") int k,
                                                 @RequestParam(defaultValue = "all") String topic) {
        return ResponseEntity.ok(service.runQuick(k, topic));
    }

    /**
     * RGB quick report (offline-only). This calls TrainingJobRunner once and writes a report file.
     */
    @GetMapping("/rgb")
    public ResponseEntity<?> rgb() {
        TrainingJobRunner runner = autoEvolveRunner.getIfAvailable();
        if (runner == null) {
            return ResponseEntity.status(503).body("TrainingJobRunner not available");
        }
        try {
            RgbSoakReport r = runner.runOnce(false, "soak_api");
            if (r == null) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("rgb soak failed: " + e.getMessage());
        }
    }
}
