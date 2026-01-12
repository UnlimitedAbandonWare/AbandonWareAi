package com.abandonware.ai.telemetry;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: SseKeys
 * 역할(Role): Class
 * 관련 기능(Tags): SSE Telemetry Publisher
 * 소스 경로: app/src/main/java/com/abandonware/ai/telemetry/SseKeys.java
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
// Module: com.abandonware.ai.telemetry.SseKeys
// Role: config
// Feature Flags: telemetry
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.telemetry.SseKeys
role: config
flags: [telemetry]
*/
class SseKeys {
    public static final String EVT_STAGE = "stage";
    public static final String EVT_TIME  = "t";
    public static final String EVT_REQID = "reqId";
    private SseKeys(){}
}