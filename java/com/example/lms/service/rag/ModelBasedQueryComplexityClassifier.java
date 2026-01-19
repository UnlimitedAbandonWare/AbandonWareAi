
package com.example.lms.service.rag;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.translate.TranslateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.io.IOException;


import ai.djl.MalformedModelException;   // ★ 추가

import java.nio.file.Paths;                // ★ 추가
import java.nio.file.Path;                 // 🔹 추가

/**
 * 경량 ONNX/코어ML 모델을 활용해 쿼리 복잡도를 분류하는 구현체.
 * 모델 경로가 비어 있으면 자동으로 비활성화되어 게이트는 규칙-기반 로직을 사용한다.
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
        model.load(path);                   // DJL 1.0.1 API - Path 필요
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
            
            // Lightweight feature extractor without external dependencies.
            // Produces an 8-dim feature vector expected by a generic small classifier:
            // [log(1+len), log(1+tokens), log(1+punct+clauses), hasQword, hasLogic, hasDigit, hasQuote, typeTokenRatio]
            String s = (input == null) ? "" : input.trim();
            try {
                int len = s.length();
                String[] rough = s.isEmpty() ? new String[0] : s.split("\\s+");
                java.util.List<String> tokens = new java.util.ArrayList<>();
                for (String t : rough) {
                    t = t.replaceAll("[^\uAC00-\uD7A3A-Za-z0-9]", "");
                    if (t.length() >= 2) tokens.add(t.toLowerCase(java.util.Locale.ROOT));
                }
                int tokenCount = tokens.size();
                int punct = s.replaceAll("[^.,!?;:·/* ... *&#47;-]", "").length();
                String low = s.toLowerCase(java.util.Locale.ROOT);
                String[] qwords = new String[]{"who","what","when","where","why","how","which","무엇","언제","어디","왜","어떻게","어느","누구"};
                int hasQWord = 0;
                for (String q : qwords) { if (low.contains(q)) { hasQWord = 1; break; } }
                String[] logic = new String[]{" and "," or ","&&","||"," vs ","비교","차이"," 그리고 "," 또는 "," 및 "};
                int hasLogic = 0;
                for (String l : logic) { if (low.contains(l)) { hasLogic = 1; break; } }
                int hasDigit = java.util.regex.Pattern.compile("\\d").matcher(s).find() ? 1 : 0;
                int hasQuote = (s.indexOf('"') >= 0 || s.indexOf('\'') >= 0) ? 1 : 0;
                int clauses = Math.max(1, s.split("[.!?;/* ... *&#47;]+").length);
                int punctClauses = punct + clauses - 1;
                java.util.Set<String> uniq = new java.util.HashSet<>(tokens);
                double ttr = (tokenCount == 0) ? 0.0 : (double) uniq.size() / (double) tokenCount;

                double[] feat = new double[]{
                    Math.log(1.0 + len),
                    Math.log(1.0 + tokenCount),
                    Math.log(1.0 + punctClauses),
                    hasQWord,
                    hasLogic,
                    hasDigit,
                    hasQuote,
                    ttr
                };
                ai.djl.ndarray.NDManager manager = ctx.getNDManager();
                ai.djl.ndarray.NDArray arr = manager.create(feat).reshape(1, feat.length);
                return new ai.djl.ndarray.NDList(arr);
            } catch (Exception ex) {
                ai.djl.ndarray.NDManager manager = ctx.getNDManager();
                ai.djl.ndarray.NDArray arr = manager.create(new float[]{0f,0f,0f,0f,0f,0f,0f,0f}).reshape(1, 8);
                return new ai.djl.ndarray.NDList(arr);
            }

        }
    }
}