package com.abandonware.ai.service.rag.fusion;

/**
 * Minimal Marchenko-Pastur style soft clamp.
 * In absence of per-source variance statistics, this acts as a safe identity-ish clamp to [0,1.5],
 * which can be tightened once empirical sigma^2 and q=n/m are logged via Soak.
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: MarchenkoPasturNormalizer
 * 역할(Role): Class
 * 관련 기능(Tags): RAG Fusion
 * 소스 경로: lms-core/src/main/java/com/abandonware/ai/service/rag/fusion/MarchenkoPasturNormalizer.java
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
final 
// [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
// Module: com.abandonware.ai.service.rag.fusion.MarchenkoPasturNormalizer
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.MarchenkoPasturNormalizer
role: config
*/
class MarchenkoPasturNormalizer {
    double clamp(double s) {
        // Soft clamp: negative to 0, overly large to 1.5
        if (Double.isNaN(s)) return 0.0;
        if (s < 0) return 0.0;
        if (s > 1.5) return 1.5;
        return s;
    }
}