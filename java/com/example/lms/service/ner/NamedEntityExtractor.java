package com.example.lms.service.ner;

import java.util.List;



/**
 * 주어진 한국어/영문 혼용 텍스트에서 고유명사(인물, 아이템, 지역, 게임 캐릭터 등)를 추출합니다.
 * 중복이 제거된 짧은 표제어 리스트를 반환합니다.
 */
public interface NamedEntityExtractor {
    List<String> extract(String text);
}