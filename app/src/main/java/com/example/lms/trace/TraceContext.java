package com.example.lms.trace;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: TraceContext
 * 역할(Role): Class
 * 관련 기능(Tags): Request Correlation Tracing
 * 소스 경로: app/src/main/java/com/example/lms/trace/TraceContext.java
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
// Module: com.example.lms.trace.TraceContext
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: unknown.
// /
/* agent-hint:
id: com.example.lms.trace.TraceContext
role: config
*/
class TraceContext {
    private static final ThreadLocal<TraceContext> TL = ThreadLocal.withInitial(TraceContext::new);
    private boolean ruleBreak;
    private String mode;

    public static TraceContext current() { return TL.get(); }

    public boolean isRuleBreak() { return ruleBreak; }
    public void setRuleBreak(boolean ruleBreak) { this.ruleBreak = ruleBreak; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

private long deadlineNanos = -1L;
private final java.util.Map<String,Object> flags = new java.util.HashMap<>();
public TraceContext startWithBudget(java.time.Duration budget) {
  if (budget != null && !budget.isZero() && !budget.isNegative())
    this.deadlineNanos = System.nanoTime() + budget.toNanos();
  return this;
}
public long remainingMillis() {
  if (deadlineNanos <= 0) return Long.MAX_VALUE;
  return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000);
}
public void setFlag(String key, Object val){ flags.put(key, val); }
public Object getFlag(String key){ return flags.get(key); }
}