
package com.example.lms.service.rag;

import ai.djl.Model;
import ai.djl.MalformedModelException;   // ★ 추가
import ai.djl.inference.Predictor;
import ai.djl.translate.TranslateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Paths;                // ★ 추가
import java.nio.file.Path;                 // 🔹 추가
import java.io.IOException;

/**
 * 경량 ONNX/코어ML 모델을 활용해 쿼리 복잡도를 분류하는 구현체.
 * 모델 경로가 비어 있으면 자동으로 비활성화되어 게이트는 규칙‑기반 로직을 사용한다.
 */
@Component
public class ModelBasedQueryComplexityClassifier implements QueryComplexityClassifier {

    @Value("${rag.queryComplexity.model.path:}")
    private String modelPath;

    private Predictor<String, QueryComplexityGate.Level> predictor;

    @PostConstruct
    void init() throws IOException, MalformedModelException {
        if (modelPath == null || modelPath.isBlank()) {
            return;            // 분류기 비활성화
        }
        Model model = Model.newInstance("query-complexity");
        Path path = Paths.get(modelPath);   // String → Path 변환
        model.load(path);                   // DJL 1.0.1 API – Path 필요
        predictor = model.newPredictor(new QueryComplexityTranslator());
    }

    @Override
    public QueryComplexityGate.Level classify(String query) {
        if (predictor == null) {
            return QueryComplexityGate.Level.AMBIGUOUS; // 안전 기본값
        }
        try {
            return predictor.predict(query == null ? "" : query);
        } catch (TranslateException e) {
            return QueryComplexityGate.Level.AMBIGUOUS; // 추론 오류 시 폴백
        }
    }

    /** 문자열 ↔ enum 매핑용 Translator */
    private static class QueryComplexityTranslator implements ai.djl.translate.Translator<String, QueryComplexityGate.Level> {
        @Override
        public QueryComplexityGate.Level processOutput(ai.djl.translate.TranslatorContext ctx, ai.djl.ndarray.NDList list) {
            int maxIdx = list.singletonOrThrow().argMax().getInt();
            return QueryComplexityGate.Level.values()[maxIdx];
        }

        @Override
        public ai.djl.ndarray.NDList processInput(ai.djl.translate.TranslatorContext ctx, String input) {
            // Placeholder: connect tokenizer pre-processing logic here.
            throw new UnsupportedOperationException("Tokenizer not implemented yet");
        }
    }
}
