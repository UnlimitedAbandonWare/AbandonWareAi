// src/main/java/com/example/lms/service/correction/DefaultQueryCorrectionService.java
package com.example.lms.service.correction;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;



/**
 * 아주 가벼운 전처리:
 * - 따옴표/백틱/스마트쿼트 제거(공백으로 치환)
 * - 유니코드 제로폭 문자 제거
 * - 다양한 대시 문자 통일(- - − → -)
 * - 양끝 특수문자 정리
 * - 공백 정규화
 */
@Service
@Primary
public class DefaultQueryCorrectionService implements QueryCorrectionService {

    @Override
    public String correct(String input) {
        if (input == null) return "";
        String s = input;

        // 1) 따옴표/백틱/스마트쿼트 → 공백 또는 표준화
        s = s.replace("“", "\"")
             .replace("”", "\"")
             .replace("‘", "'")
             .replace("’", "'")
             .replace("`", " ")
             .replace("\"", " ")
             .replace("'", " ");

        // 2) 유니코드 제로폭 문자 제거
        s = s.replaceAll("[\u200B\u200C\u200D\uFEFF]", "");

        // 3) 다양한 대시 문자 통일
        s = s.replaceAll("[--−]+", "-");

        // 4) 양끝 특수문자 정리(문장 내부는 보존)
        //    Punct(구두점), Sm(수학기호), Sk(수정기호) 범주를 양쪽 끝에서만 제거
        s = s.replaceAll("^[\\p{Punct}\\p{Sm}\\p{Sk}]+", "");
        s = s.replaceAll("[\\p{Punct}\\p{Sm}\\p{Sk}]+$", "");

        // 5) 공백 정규화
        s = s.replace('\u00A0', ' ')        // NBSP → SPACE
             .replaceAll("\\s+", " ")
             .trim();

        return s;
    }
}