/* 
//* Extracted formula module for orchestration
//* Source zip: src111_mx.zip
//* Source path: app/src/main/java/com/nova/protocol/alloc/RiskKAllocator.java
//* Extracted: 2025-10-20T15:26:37.330260Z
//*/
package com.nova.protocol.alloc;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: RiskKAllocator
 * 역할(Role): Class
 * 소스 경로: addons/formulas_pack/addons/formulas/java/com/nova/protocol/alloc/RiskKAllocator.java
 *
 * 연결 포인트(Hooks):
 *   - DI/협력 객체는 @Autowired/@Inject/@Bean/@Configuration 스캔으로 파악하세요.
 *   - 트레이싱 헤더: X-Request-Id, X-Session-Id (존재 시 전체 체인에서 전파).
 *
 * 과거 궤적(Trajectory) 추정:
 *   - 본 클래스가 속한 모듈의 변경 이력은 /MERGELOG_*, /PATCH_NOTES_*, /CHANGELOG_* 문서를 참조.
 *   - 동일 기능 계통 클래스: 같은 접미사(Service/Handler/Controller/Config) 및 동일 패키지 내 유사명 검색.
 *
 * 안전 노트: 본 주석 추가는 코드 실행 경로를 변경하지 않습니다(주석 전용).
 */

public final 
// [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
// Module: com.nova.protocol.alloc.RiskKAllocator
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.nova.protocol.alloc.RiskKAllocator
role: config
*/
class RiskKAllocator {

  /** Softmax-style allocation with optional per-source bonus and floor. */
  public int[] alloc(double[] logits, double[] bonus, int K, double T, int[] floor){
    if (logits == null || logits.length == 0) return new int[]{K};
    int n = logits.length;
    double Z = 0.0;
    double[] e = new double[n];
    double temp = Math.max(1e-3, T);
    for (int i = 0; i < n; i++) {
      double b = (bonus != null && i < bonus.length) ? bonus[i] : 0.0;
      e[i] = Math.exp((logits[i] + b) / temp);
      Z += e[i];
    }
    int[] k = new int[n];
    int sum = 0;
    for (int i = 0; i < n; i++) {
      int f = (floor != null && i < floor.length) ? floor[i] : 0;
      k[i] = Math.max(f, (int) Math.round(K * (e[i] / Z)));
      sum += k[i];
    }
    // Adjust to satisfy total K
    while (sum > K) {
      for (int i = 0; i < n && sum > K; i++) {
        int f = (floor != null && i < floor.length) ? floor[i] : 0;
        if (k[i] > f) { k[i]--; sum--; }
      }
    }
    while (sum < K) { k[0]++; sum++; }
    return k;
  }

  /** CVaR-aware allocation: boost logits by a tail factor derived from scores (upper tail CVaR). */
  public int[] allocCvarAware(double[] logits, double[] scores, int K, double T){
    double tail = 0.0;
    try {
      com.nova.protocol.fusion.CvarAggregator agg = new com.nova.protocol.fusion.CvarAggregator();
      tail = agg.cvar(scores, 0.10);
      // fuse base avg (assumed mean≈0.5) with tail; λ=0.6 slightly favors tail
      tail = agg.fuse(0.5, tail, 0.6);
    } catch (Throwable ignore) {}
    double boost = Math.max(0.0, Math.min(1.0, tail));
    double[] bonus = new double[logits == null ? 0 : logits.length];
    for (int i = 0; i < bonus.length; i++) bonus[i] = boost;
    return alloc(logits, bonus, K, T, null);
  }
}