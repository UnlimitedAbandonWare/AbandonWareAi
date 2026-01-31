// src/main/java/service/rag/planner/SelfAskResult.java
package service.rag.planner;

import java.util.List;
import java.util.Map;

public class SelfAskResult {
    public static class SubQuery {
        public final SubQueryKind kind;
        public final String text;
        public SubQuery(SubQueryKind k, String t) { this.kind = k; this.text = t; }
    }
    public final String original;
    public final List<SubQuery> subQueries;
    public final List<Map<String, Object>> fusedTopK; // 표준 컨텍스트 {id,title,snippet,source,score,rank}
    public SelfAskResult(String original, List<SubQuery> subQueries, List<Map<String, Object>> fusedTopK) {
        this.original = original; this.subQueries = subQueries; this.fusedTopK = fusedTopK;
    }
}