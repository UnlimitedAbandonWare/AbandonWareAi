package com.nova.protocol.fusion;

import java.util.Arrays;

/** Tail-Weighted Power Mean (TWPM) with dynamic exponent from tail index (CVaR/mean). */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: TailWeightedPowerMeanFuser
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/nova/protocol/fusion/TailWeightedPowerMeanFuser.java
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
// Module: com.nova.protocol.fusion.TailWeightedPowerMeanFuser
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.nova.protocol.fusion.TailWeightedPowerMeanFuser
role: config
*/
class TailWeightedPowerMeanFuser {

  /**
   * @param x   scores in [0,1]
   * @param w   non-negative weights, same length as x
   * @param p0  base power
   * @param a   amplification for tail index, i.e., p = p0 + a*(CVaR/mean - 1)
   */
  public double fuse(double[] x, double[] w, double p0, double a){
    if (x==null||w==null||x.length==0||x.length!=w.length) return 0.0;
    // mean
    double sum=0; for(double v:x) sum+=Math.max(0.0, v); double mean = sum/Math.max(1, x.length);
    // CVaR (upper 20% by default via quantile proxy)
    double[] s = x.clone(); Arrays.sort(s);
    int k = Math.max(1, (int)Math.ceil(0.20 * s.length)); // 20% tail
    double tail=0; for(int i=s.length-k;i<s.length;i++) tail += s[i];
    double cvar = tail / k;

    double tailIndex = (mean>1e-12)? (cvar/mean) : 1.0;
    double p = p0 + a * (tailIndex - 1.0);
    p = Math.max(1.0, Math.min(8.0, p));

    double num=0, den=0;
    for (int i=0;i<x.length;i++){
      double xi = Math.max(0.0, Math.min(1.0, x[i]));
      double wi = Math.max(0.0, w[i]);
      num += wi * Math.pow(xi + 1e-12, p);
      den += wi;
    }
    if (den <= 0) return 0.0;
    double m = Math.pow(num/den, 1.0/Math.max(1e-6,p));
    return Math.max(0.0, Math.min(1.0, m));
  }
}