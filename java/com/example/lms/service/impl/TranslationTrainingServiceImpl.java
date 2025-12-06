package com.example.lms.service.impl;

import com.example.lms.domain.TranslationRule;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.repository.RuleRepository;
import com.example.lms.service.TranslationTrainingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




@Service
@RequiredArgsConstructor
public class TranslationTrainingServiceImpl implements TranslationTrainingService {
    private static final Logger log = LoggerFactory.getLogger(TranslationTrainingServiceImpl.class);

    // [수정] RuleRepository 의존성 주입
    private final RuleRepository ruleRepository;

    @Override
    @Transactional
    // [수정] 파라미터 타입을 인터페이스와 동일하게 변경
    public int learnRuleFromChatHistory(List<? extends ChatRequestDto.Message> history) {
        int learned = 0;

        for (int i = 0; i < history.size() - 1; i++) {
            ChatRequestDto.Message userMsg   = history.get(i);
            ChatRequestDto.Message assistant = history.get(i + 1);

            if (!"user".equals(userMsg.getRole()) ||
                    !"assistant".equals(assistant.getRole())) {
                continue;
            }

            if (ruleRepository.existsByPatternAndLangAndPhase(
                    userMsg.getContent(), "ko", RulePhase.PRE)) {
                continue;
            }

            TranslationRule rule = TranslationRule.builder()
                    .lang("ko")
                    .phase(RulePhase.PRE)
                    .pattern(userMsg.getContent())
                    .replacement(assistant.getContent())
                    .description("chat-ui auto-learned")
                    .enabled(true)
                    .build();

            ruleRepository.save(rule);
            learned++;
            log.info("🆕 rule learned: '{}' → '{}'", rule.getPattern(), rule.getReplacement());
        }
        return learned;
    }

    @Override
    @Transactional
    public int learnFromCorrectedSamples() {
        // Implementation shim: load user-corrected translation samples from the DB for regularization.
        return 0;
    }

    @Override
    public List<TranslationRule> getAllRules() {
        return ruleRepository.findAll();
    }
}