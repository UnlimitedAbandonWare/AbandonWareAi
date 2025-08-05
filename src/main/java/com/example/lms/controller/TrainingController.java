// src/main/java/com/example/lms/controller/TrainingController.java
package com.example.lms.controller;

import com.example.lms.domain.TrainingJob;
import com.example.lms.service.TrainingService; // 이 경로가 정확한지 확인
import com.example.lms.service.TranslationTrainingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ✨ 비동기 학습(Job) + 즉시 학습(train-now) API를 모두 제공하는 단일 컨트롤러
 *
 * <pre>
 *   POST /api/train               : 비동기 학습 Job 생성 → jobId 반환
 *   GET  /api/train/{id}/status   : Job 진행률/상태 조회
 *   POST /api/translate/train-now : 수정된 샘플을 즉시 학습(동기) – 건수 반환
 * </pre>
 */

// src/main/java/com/example/lms/controller/TrainingController.java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/train")          // ★ 베이스 경로를 /api/train 으로!
public class TrainingController {

    private final TrainingService            asyncTrainingSvc;
    private final TranslationTrainingService instantTrainingSvc;

    /* ---------- 1. 비동기 학습(Job) ---------- */

    /** 학습 Job 시작 – jobId 반환 */
    @PostMapping                           // → POST /api/train
    public Long startAsyncTraining() {
        return asyncTrainingSvc.startTraining();
    }

    /** Job 진행률/상태 조회 */
    @GetMapping("/{id}/status")            // → GET  /api/train/{id}/status
    public TrainingJob jobStatus(@PathVariable Long id) {
        return asyncTrainingSvc.status(id);
    }

    /* ---------- 2. 즉시 학습(train-now) ---------- */

    /** 수정된 샘플을 바로 학습 – 처리 건수 반환 */
    @PostMapping("/train-now")             // → POST /api/train/train-now
    public ResponseEntity<Map<String, Object>> trainNow() {
        int learned = instantTrainingSvc.learnFromCorrectedSamples();
        return ResponseEntity.ok(Map.of(
                "message", "수동 학습이 완료되었습니다.",
                "learnedSamples", learned
        ));
    }
}
