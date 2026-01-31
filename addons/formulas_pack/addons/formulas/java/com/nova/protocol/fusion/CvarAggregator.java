/* 
//* Extracted formula module for orchestration
//* Source zip: src111_mx.zip
//* Source path: app/src/main/java/com/nova/protocol/fusion/CvarAggregator.java
//* Extracted: 2025-10-20T15:26:37.308704Z
//*/
package com.nova.protocol.fusion;

import java.util.Arrays;

/** CVaR@alpha upper-tail aggregator with smooth clamp. */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: CvarAggregator
 * 역할(Role): Class
 * 소스 경로: addons/formulas_pack/addons/formulas/java/com/nova/protocol/fusion/CvarAggregator.java
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
// Module: com.nova.protocol.fusion.CvarAggregator
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.nova.protocol.fusion.CvarAggregator
role: config
*/
class CvarAggregator {

  /** CVaR over the upper tail: average of the top (alpha) fraction. alpha in (0,1]. */
  public double cvar(double[] x, double alpha){
    if (x==null || x.length==0) return 0.0;
    double[] s = x.clone(); Arrays.sort(s);
    int n = s.length;
    int k = Math.max(1, (int)Math.ceil(Math.max(0.0, Math.min(1.0, alpha)) * n));
    double sum = 0.0;
    for (int i = n - k; i < n; i++) sum += s[i];
    return sum / k;
  }

  /** Mix base score and CVaR, then apply a smooth clamp to [0,1] range. */
  public double fuse(double base, double cvar, double lambda){
    double mix = (1.0 - lambda) * base + lambda * cvar;
    // Smooth limiter to avoid runaway; see BodeClamp (c controls softness)
    double limited = BodeClamp.apply(mix, 1.2);
    return limited;
  }
}