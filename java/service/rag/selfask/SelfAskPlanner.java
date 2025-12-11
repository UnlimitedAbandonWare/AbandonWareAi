package service.rag.selfask;

import java.util.*;
import com.example.rag.fusion.WeightedRRF;

/**
 * Minimal isolated SelfAskPlanner (legacy namespace).
 * This class is kept to satisfy compile-time compatibility for older modules.
 * It generates three sub-questions (BQ/ER/RC) and performs a safe RRF fuse
 * over per-branch result lists. Retrieval and cross-encoder reranking are
 * intentionally omitted here to avoid stale dependencies.
 */
public class SelfAskPlanner {

  public static class SubQ {
    public final String type;
    public final String q;
    public SubQ(String t, String q){ this.type=t; this.q=q; }
  }

  /**
   * Entry point preserved for backward compatibility.
   * @param userQ  original user query
   * @param topK   number of results to return after fusion
   * @return fused list (may be empty when no branch has hits)
   */
  public List<Map<String,Object>> plan(String userQ, int topK) {
    List<List<Map<String,Object>>> perBranch = new ArrayList<>();
    for (SubQ s : generateSubQs(userQ)) {
      // No actual retrieval here; keep the structure in case callers inspect "branch"
      List<Map<String,Object>> hits = new ArrayList<>();
      perBranch.add(hits);
    }
    // Fuse (robust to empty lists)
    List<Map<String,Object>> fused = WeightedRRF.fuse(perBranch, Math.max(1, topK));
    return fused;
  }

  // Legacy signature that previously accepted a TraceContext - kept as a no-op wrapper.
  public List<Map<String,Object>> plan(String userQ, Object traceContext, int topK) {
    return plan(userQ, topK);
  }

  private List<SubQ> generateSubQs(String q){
    String t = q == null ? "" : q.trim();
    return java.util.Arrays.asList(
      new SubQ("BQ", t.endsWith("?")? t : t + "?"),
      new SubQ("ER", t + " 의 동의어/별칭/오타를 포함한 설명"),
      new SubQ("RC", t + " 와(과) 관련된 원인/결과/지표/연관개념")
    );
  }
}