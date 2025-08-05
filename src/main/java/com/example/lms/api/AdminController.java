package com.example.lms.api;

import com.example.lms.dto.FineTuningOptionsDto;
import com.example.lms.service.FineTuningService;
import com.theokanning.openai.fine_tuning.FineTuningJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 관리자 전용 REST 컨트롤러 (최종 통합본)
 * - 파인튜닝 작업 생성, 목록 조회, 상태 확인 등 관리자 기능 제공
 * - '/api/admin/fine-tuning' 경로 기반, ADMIN 권한 필수
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/fine-tuning")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

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
        log.info("파인튜닝 요청 수신: {}", options);
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
            log.error("파인튜닝 파일 처리 중 오류 발생", ioe);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "파일 처리 중 오류가 발생했습니다: " + ioe.getMessage())
            );
        } catch (Exception ex) {
            log.error("파인튜닝 작업 생성 중 오류 발생", ex);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "OpenAI API 호출 중 오류가 발생했습니다: " + ex.getMessage())
            );
        }
    }

    /**
     * 모든 파인튜닝 작업 목록 조회
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<FineTuningJob>> listJobs() {
        return ResponseEntity.ok(fineTuningService.listFineTuningJobs());
    }

    /**
     * 특정 파인튜닝 작업 상태 조회
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> checkStatus(@PathVariable String jobId) {
        Optional<FineTuningJob> jobOptional = fineTuningService.checkJobStatus(jobId);
        return jobOptional
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(
                        Map.of("error", "Job ID '" + jobId + "'를 찾을 수 없습니다.")
                ));
    }
}
