
package com.example.lms.api;

import com.example.lms.dto.FineTuningOptionsDto;
import com.example.lms.dto.FineTuningJobDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.FineTuningService;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


// (FineTuningJob import removed)


/**
 * 관리자 전용 REST 컨트롤러 (최종 통합본)
 * - 파인튜닝 작업 생성, 목록 조회, 상태 확인 등 관리자 기능 제공
 * - '/api/admin/fine-tuning' 경로 기반, ADMIN 권한 필수
 */
@RestController
@RequestMapping("/api/admin/fine-tuning")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final FineTuningService fineTuningService;

    /**
     * 파인튜닝 작업 시작
     * @param options 요청 옵션
     */
    @PostMapping("/start")
    public ResponseEntity<?> startFineTuning(@RequestBody(required = false) FineTuningOptionsDto options) {
        if (options == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "파인튜닝 옵션을 제공해주세요.")
            );
        }
        log.info("[AWX][fine-tuning] request {}", fineTuningOptionsSummary(options));
        if (fineTuningService.isRemoteFineTuningDisabled()) {
            String disabledReason = fineTuningService.remoteDisabledReason();
            traceFineTuningDisabled(disabledReason);
            log.warn("[AWX][fine-tuning] provider-disabled disabledReason={}", disabledReason);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    publicFineTuningDisabled(disabledReason)
            );
        }
        try {
            String jobId = fineTuningService.startFineTuningJob(options);
            if (jobId == null) {
                return ResponseEntity.badRequest().body(
                        Map.of("message", "훈련 조건을 만족하는 데이터가 부족하여 작업을 시작할 수 없습니다.")
                );
            }
            return ResponseEntity.ok(
                    Map.of("message", "파인튜닝 작업이 성공적으로 시작되었습니다.", "jobId", jobId)
            );
        } catch (IOException ioe) {
            log.error("[AWX][fine-tuning] file-processing-failed type={} errorHash={} errorLength={}",
                    ioe.getClass().getSimpleName(),
                    SafeRedactor.hashValue(messageOf(ioe)),
                    messageLength(ioe));
            return ResponseEntity.internalServerError().body(
                    publicFineTuningError("file_processing_failed", ioe)
            );
        } catch (Exception ex) {
            log.error("[AWX][fine-tuning] job-create-failed type={} errorHash={} errorLength={}",
                    ex.getClass().getSimpleName(),
                    SafeRedactor.hashValue(messageOf(ex)),
                    messageLength(ex));
            return ResponseEntity.internalServerError().body(
                    publicFineTuningError("job_create_failed", ex)
            );
        }
    }

    /**
     * 모든 파인튜닝 작업 목록 조회
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<FineTuningJobDto>> listJobs() {
        return ResponseEntity.ok(fineTuningService.listFineTuningJobs());
    }

    /**
     * 특정 파인튜닝 작업 상태 조회
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> checkStatus(@PathVariable String jobId) {
        Optional<FineTuningJobDto> jobOptional = fineTuningService.checkJobStatus(jobId);
        return jobOptional
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(
                        publicFineTuningJobNotFound(jobId)
                ));
    }

    private static Map<String, Object> fineTuningOptionsSummary(FineTuningOptionsDto options) {
        return Map.of(
                "optionsHash", SafeRedactor.hashValue(String.valueOf(options)),
                "epochs", options.epochs(),
                "hasLearningRate", options.learningRate() != null && options.learningRate().isPresent(),
                "hasBatchSize", options.batchSize() != null && options.batchSize().isPresent(),
                "qualityWeightingPresent", options.qualityWeighting() != null
        );
    }

    private static Map<String, Object> publicFineTuningError(String code, Exception ex) {
        String message = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        String messageHash = SafeRedactor.hashValue(message);
        return Map.of(
                "error", code,
                "errorHash", messageHash == null ? "" : messageHash,
                "errorLength", message.length()
        );
    }

    private static Map<String, Object> publicFineTuningDisabled(String disabledReason) {
        return Map.of(
                "error", "fine_tuning_provider_disabled",
                "disabledReason", disabledReason == null || disabledReason.isBlank()
                        ? "remote_fine_tuning_endpoint_unavailable"
                        : disabledReason
        );
    }

    private static void traceFineTuningDisabled(String disabledReason) {
        String safeReason = SafeRedactor.traceLabelOrFallback(
                disabledReason == null || disabledReason.isBlank()
                        ? "remote_fine_tuning_endpoint_unavailable"
                        : disabledReason,
                "unknown");
        TraceStore.put("fineTuning.providerDisabled", true);
        TraceStore.put("fineTuning.disabledReason", safeReason);
        TraceStore.put("fineTuning.skipped.reason", safeReason);
    }

    private static Map<String, Object> publicFineTuningJobNotFound(String jobId) {
        String id = jobId == null ? "" : jobId;
        String idHash = SafeRedactor.hashValue(id);
        return Map.of(
                "error", "job_not_found",
                "jobIdHash", idHash == null ? "" : idHash,
                "jobIdLength", id.length()
        );
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }
}
