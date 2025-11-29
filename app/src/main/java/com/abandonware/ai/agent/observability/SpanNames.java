package com.abandonware.ai.agent.observability;


/** Canonical span names used across the agent. */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: SpanNames
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/agent/observability/SpanNames.java
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
// Module: com.abandonware.ai.agent.observability.SpanNames
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.agent.observability.SpanNames
role: config
*/
class SpanNames {
    private SpanNames() {}
    public static final String PLAN = "plan";
    public static final String RAG_RETRIEVE = "rag.retrieve";
    public static final String CRITIC = "critic";
    public static final String SYNTH = "synth";
    public static final String JOBS_ENQUEUE = "jobs.enqueue";
    public static String tool(String id) { return "tool:" + id; }
}