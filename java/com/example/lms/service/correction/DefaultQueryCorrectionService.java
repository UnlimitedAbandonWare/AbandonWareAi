package com.example.lms.service.correction;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DefaultQueryCorrectionService implements QueryCorrectionService {

    @Override
    public String correct(String input) {
        if (input == null) return "";
        // 아주 가벼운 교정만: 공백/양끝 트림
        String s = input.replaceAll("\\s+", " ").trim();
        // 필요시 도메인 교정 규칙을 더 추가
        return s;
    }
}
