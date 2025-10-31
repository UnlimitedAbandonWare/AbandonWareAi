package com.example.lms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;



@Service // bean name: inputDistillationService
public class InputDistillationService {

    @Value("${abandonware.input.distillation.enabled:true}")
    private boolean enabled;

    @Value("${abandonware.augment.max-prior-chars:1200}")
    private int maxPriorChars;

    /** Prior answer를 증강 투입용으로 축약(간단 절단 폴백) */
    public String distillForAugment(String priorAnswer) {
        if (!enabled || priorAnswer == null) return priorAnswer;
        return priorAnswer.length() > maxPriorChars
                ? priorAnswer.substring(0, maxPriorChars)
                : priorAnswer;
    }
}