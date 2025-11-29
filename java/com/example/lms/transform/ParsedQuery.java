package com.example.lms.transform;

import java.util.List;



/** LLM이 반환하는 구조화 결과 */
public record ParsedQuery(
        String subject,
        String intent,
        List<String> constraints   // e.g. ["marketplace:중고나라","device:K8 Plus"]
) {}