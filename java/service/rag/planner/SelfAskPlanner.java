// src/main/java/service/rag/planner/SelfAskPlanner.java
package service.rag.planner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 라이트 버전:
 *  - LLM 미의존, 규칙 기반 3분기
 *  - 각 분기 질의에 대해 web/vector 동시 검색 → 결과 합치기(RRF는 기존 빈을 주입받아 사용 권장)
 *  - 본 스텁은 검색기/퓨저 인터페이스만 정의하고, 미존재 시 안전 폴백(빈 리스트)로 동작
 */
@Component
public class SelfAskPlanner {

    public interface Retriever {
        // 검색 결과는 표준 컨텍스트 형태의 맵 리스트 {id,title,snippet,source,score,rank}
        List<Map<String, Object>> retrieve(String query, int topK);
    }
    public interface Fuser {
        List<Map<String, Object>> fuse(List<List<Map<String, Object>>> results, int topK);
    }

    private final SynonymDictionary synonyms;
    private final Optional<Retriever> webRetriever;     // AnalyzeWebSearchRetriever 등에 연결 권장
    private final Optional<Retriever> vectorRetriever;  // FederatedEmbeddingStore 기반 리트리버
    private final Optional<Fuser> rrfFuser;             // WeightedRRF

    @Autowired
    public SelfAskPlanner(SynonymDictionary synonyms,
                          @Qualifier("webRetriever") Optional<Retriever> webRetriever,
                          @Qualifier("vectorRetriever") Optional<Retriever> vectorRetriever,
                          @Qualifier("rrfFuser") Optional<Fuser> rrfFuser) {
        this.synonyms = synonyms;
        this.webRetriever = webRetriever;
        this.vectorRetriever = vectorRetriever;
        this.rrfFuser = rrfFuser;
    }

    public SelfAskResult plan(String original, int kEach, int kFinal) {
        List<SelfAskResult.SubQuery> subs = generate3Way(original);
        // 각 sub에 대해 web/vector 동시 검색
        List<List<Map<String,Object>>> batches = new ArrayList<>();
        for (SelfAskResult.SubQuery sq : subs) {
            List<Map<String,Object>> merged = new ArrayList<>();
            webRetriever.ifPresent(r -> merged.addAll(safe(r.retrieve(sq.text, kEach))));
            vectorRetriever.ifPresent(r -> merged.addAll(safe(r.retrieve(sq.text, kEach))));
            batches.add(merged);
        }
        // RRF 융합(미주입 시 상위 score 정렬 대체)
        List<Map<String,Object>> fused = rrfFuser
            .map(f -> f.fuse(batches, kFinal))
            .orElseGet(() -> batches.stream()
                .flatMap(List::stream)
                .sorted((a,b) -> Double.compare(score(b), score(a)))
                .limit(kFinal)
                .collect(Collectors.toList()));
        return new SelfAskResult(original, subs, fused);
    }

    private List<SelfAskResult.SubQuery> generate3Way(String q) {
        String base = q == null ? "" : q.trim();
        String bq = base + " 정의는 무엇인가? (개념, 용어 정리)";
        List<String> al = synonyms.expandAliases(base);
        String er = (al.size() > 1)
            ? String.format("%s (%s) 동의어/별칭 기준으로 검색", base, String.join(", ", al))
            : base + " (동의어/별칭 포함)";
        String rc = base + " 와(과) 관련된 원인/결과/관계는? (연관 개체/KPI/요인)";

        return List.of(
            new SelfAskResult.SubQuery(SubQueryKind.BQ_DEFINITION, bq),
            new SelfAskResult.SubQuery(SubQueryKind.ER_ALIAS, er),
            new SelfAskResult.SubQuery(SubQueryKind.RC_RELATION, rc)
        );
    }

    private static List<Map<String,Object>> safe(List<Map<String,Object>> v) {
        return v == null ? List.of() : v;
    }
    private static double score(Map<String,Object> m) {
        Object s = m.getOrDefault("score", 0.0);
        return (s instanceof Number) ? ((Number)s).doubleValue() : 0.0;
    }
}