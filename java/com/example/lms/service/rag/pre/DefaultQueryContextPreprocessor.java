package com.example.lms.service.rag.pre;
import org.springframework.context.annotation.Primary;   // ✅ 추가

import org.openkoreantext.processor.OpenKoreanTextProcessorJava;
import org.openkoreantext.processor.tokenizer.KoreanTokenizer.KoreanToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import scala.collection.Seq;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 간단한 NER  위치 주입 전처리기 구현.
 * – Open‑Korean‑Text로 고유명사(NNP) 토큰을 감지해 분해를 방지.<br>
 * – 지역성 명사 사전에 매칭되면 {@code user.default-location} 값을 선행 키워드로 삽입.
 */

@Primary
public class DefaultQueryContextPreprocessor implements QueryContextPreprocessor {

    private static final Set<String> LOCATION_SENSITIVE_NOUNS = Set.of(
            "아카데미","병원","식당","맛집","학원","은행","호텔"
    );

    @Value("${user.default-location:대전}")
    private String defaultLocation;

    @Override
    public String enrich(String original) {
        if (original == null || original.isBlank()) return original;

        /* 1) 품사 태깅 */
        CharSequence normalized = OpenKoreanTextProcessorJava.normalize(original);
        Seq<KoreanToken> tokens = OpenKoreanTextProcessorJava.tokenize(normalized);
        List<KoreanToken> list  = scala.collection.JavaConverters.seqAsJavaList(tokens);

        /* 2) 고유명사(NNP) 보호 */
        String joined = list.stream()
                .map(t -> t.pos().toString().equals("NNP") ? ("\"" + t.text() + "\"") : t.text())
                .collect(Collectors.joining(" "));

        /* 3) 지역 키워드 주입 */
        boolean needsLocation = list.stream()
                .anyMatch(t -> LOCATION_SENSITIVE_NOUNS.contains(t.text()));
        if (needsLocation && !joined.contains(defaultLocation)) {
            joined = defaultLocation + " " + joined;
        }
        return joined;
    }
}