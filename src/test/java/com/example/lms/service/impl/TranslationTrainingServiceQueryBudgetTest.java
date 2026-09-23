package com.example.lms.service.impl;

import com.example.lms.domain.TranslationRule;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.repository.RuleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslationTrainingServiceQueryBudgetTest {
    @Test
    void repeatedHistoryPatternUsesOneExistenceQueryAndPreservesFirstAnswer() {
        RuleRepository repository = mock(RuleRepository.class);
        Set<String> stored = new HashSet<>();
        when(repository.existsByPatternAndLangAndPhase(anyString(), eq("ko"), eq(RulePhase.PRE)))
                .thenAnswer(call -> stored.contains(call.getArgument(0)));
        when(repository.save(any(TranslationRule.class))).thenAnswer(call -> {
            TranslationRule rule = call.getArgument(0);
            stored.add(rule.getPattern());
            return rule;
        });
        List<ChatRequestDto.Message> history = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            history.add(new ChatRequestDto.Message("user", "same pattern"));
            history.add(new ChatRequestDto.Message("assistant", "answer " + i));
        }

        assertEquals(1, new TranslationTrainingServiceImpl(repository).learnRuleFromChatHistory(history));
        ArgumentCaptor<TranslationRule> saved = ArgumentCaptor.forClass(TranslationRule.class);
        verify(repository).save(saved.capture());
        assertEquals("answer 0", saved.getValue().getReplacement());
        verify(repository, times(1)).existsByPatternAndLangAndPhase("same pattern", "ko", RulePhase.PRE);
    }

    @Test
    void existingAndNewPatternsRemainIndependentAndHistoryOrderIsPreserved() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.existsByPatternAndLangAndPhase("existing", "ko", RulePhase.PRE)).thenReturn(true);
        when(repository.save(any(TranslationRule.class))).thenAnswer(call -> call.getArgument(0));
        List<ChatRequestDto.Message> history = List.of(
                new ChatRequestDto.Message("user", "existing"), new ChatRequestDto.Message("assistant", "old"),
                new ChatRequestDto.Message("user", "existing"), new ChatRequestDto.Message("assistant", "ignored"),
                new ChatRequestDto.Message("user", "new"), new ChatRequestDto.Message("assistant", "first new"));

        assertEquals(1, new TranslationTrainingServiceImpl(repository).learnRuleFromChatHistory(history));
        verify(repository).existsByPatternAndLangAndPhase("existing", "ko", RulePhase.PRE);
        verify(repository).existsByPatternAndLangAndPhase("new", "ko", RulePhase.PRE);
        ArgumentCaptor<TranslationRule> saved = ArgumentCaptor.forClass(TranslationRule.class);
        verify(repository).save(saved.capture());
        assertEquals("new", saved.getValue().getPattern());
        assertEquals("first new", saved.getValue().getReplacement());
    }

    @Test
    void emptyAndUnpairedHistoryStillLearnNothing() {
        RuleRepository repository = mock(RuleRepository.class);
        TranslationTrainingServiceImpl service = new TranslationTrainingServiceImpl(repository);
        assertEquals(0, service.learnRuleFromChatHistory(List.of()));
        assertEquals(0, service.learnRuleFromChatHistory(List.of(new ChatRequestDto.Message("user", "unpaired"))));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void patternChecksAreNotCachedAcrossSeparateLearningCalls() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.existsByPatternAndLangAndPhase("pattern", "ko", RulePhase.PRE)).thenReturn(false, true);
        when(repository.save(any(TranslationRule.class))).thenAnswer(call -> call.getArgument(0));
        TranslationTrainingServiceImpl service = new TranslationTrainingServiceImpl(repository);
        List<ChatRequestDto.Message> history = List.of(new ChatRequestDto.Message("user", "pattern"),
                new ChatRequestDto.Message("assistant", "translation"));

        assertEquals(1, service.learnRuleFromChatHistory(history));
        assertEquals(0, service.learnRuleFromChatHistory(history));
        verify(repository, times(2)).existsByPatternAndLangAndPhase("pattern", "ko", RulePhase.PRE);
    }
}
