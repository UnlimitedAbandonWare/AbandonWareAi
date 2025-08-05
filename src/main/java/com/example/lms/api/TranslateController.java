// src/main/java/com/example/lms/api/TranslateController.java
package com.example.lms.api;

import com.example.lms.domain.TranslationRule;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.TranslationTrainingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ① POST  /api/translate/train   : 채팅 히스토리를 규칙으로 학습
 * ② GET   /api/translate/rules   : 저장된 규칙 전체 조회
 */
@RestController
@RequestMapping("/api/translate")
@RequiredArgsConstructor
@Slf4j
public class TranslateController {

    private final TranslationTrainingService trainingService;

    /* ------------------------------
       1. 수동 학습 엔드포인트
       ------------------------------ */
    @PostMapping("/train")        // 🔄 기존 "/train-now" → "/train"
    public ResponseEntity<Map<String, Object>> trainNow(
            @RequestBody List<ChatRequestDto.Message> chatHistory) {

        if (CollectionUtils.isEmpty(chatHistory)) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "학습할 대화 기록이 없습니다.")
            );
        }

        int learned = trainingService.learnRuleFromChatHistory(chatHistory);

        return ResponseEntity.ok(Map.of(
                "message", learned > 0 ? "수동 학습이 완료되었습니다." : "학습할 새로운 규칙이 없습니다.",
                "learnedSamples", learned
        ));
    }

    /* ------------------------------
       2. 규칙 목록 조회
       ------------------------------ */
    @GetMapping("/rules")
    public List<TranslationRule> rules() {
        return trainingService.getAllRules();
    }
}
