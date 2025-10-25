// src/main/java/com/example/lms/controller/TranslationController.java
package com.example.lms.controller;

import com.example.lms.dto.ChatMessageDto;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.TranslationTrainingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;


import org.springframework.util.CollectionUtils; // [수정] CollectionUtils 임포트


import static java.util.stream.Collectors.toList;

@RestController
@RequestMapping("/api/translate")
@RequiredArgsConstructor
public class TranslationController {

    private final TranslationTrainingService trainingService;

    /**
     * [수정] 채팅 로그를 받아 규칙을 학습하는 기능만 남깁니다.
     * Body에 chatHistory[] 를 포함하여 요청해야 합니다.
     *
     * POST /api/translate/train-now
     */
    @PostMapping("/train-now")
    public ResponseEntity<Map<String, Object>> trainNow(
            @RequestBody List<ChatMessageDto> chatHistory) { // [수정] required = false 제거

        /*
        // ── ① Body X : 번역 메모리 학습 (TrainingController로 역할 이전) ──
        // 이 부분은 TrainingController의 /api/train/train-now 와 기능이 중복되므로 주석 처리합니다.
        // 이로 인해 발생하던 learnRuleFromCorrectedSamples() 메소드 호출 에러가 해결됩니다.
        if (chatHistory == null || chatHistory.isEmpty()) {
            int learned = trainingService.learnRuleFromCorrectedSamples();

            return ResponseEntity.ok(Map.of(
                    "message", learned + "개의 수정된 번역 샘플을 학습했습니다.",
                    "learnedSamples", learned
            ));
        }
        */

        // ──  채팅 로그 → 규칙 학습 ───────────────────────
        // [수정] chatHistory가 비어있는 경우에 대한 예외 처리 추가
        if (CollectionUtils.isEmpty(chatHistory)) {
            return ResponseEntity.badRequest().body(Map.of("message", "학습할 대화 내용이 없습니다."));
        }

        List<ChatRequestDto.Message> msgs = chatHistory.stream()
                .map(m -> new ChatRequestDto.Message(m.getRole(), m.getContent()))
                .collect(toList());

        int learned = trainingService.learnRuleFromChatHistory(msgs);

        return ResponseEntity.ok(Map.of(
                "message", learned > 0 ? "규칙 학습 완료!" : "새로 학습할 규칙이 없습니다.",
                "learnedSamples", learned
        ));
    }
}