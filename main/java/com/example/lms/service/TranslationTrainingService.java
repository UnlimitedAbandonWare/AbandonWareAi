package com.example.lms.service;

import com.example.lms.domain.TranslationRule;
import com.example.lms.dto.ChatRequestDto;
import java.util.List;



public interface TranslationTrainingService {
    int learnRuleFromChatHistory(List<? extends ChatRequestDto.Message> messages);

    int learnFromCorrectedSamples(); // <-- 이 메소드 선언을 추가하세요.

    List<TranslationRule> getAllRules();
}