package com.nova.protocol.fusion;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: BodeClamp
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/nova/protocol/fusion/BodeClamp.java
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
// Module: com.nova.protocol.fusion.BodeClamp
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.nova.protocol.fusion.BodeClamp
role: config
*/
class BodeClamp {
  /** 과민 반응 제어: 값 폭주 억제 + [0,1] 클램프 */
  public static double apply(double x, double c){
    double y = (c>0) ? x / Math.sqrt(1 + c*x*x) : x;
    return Math.max(0.0, Math.min(1.0, y));
  }
}