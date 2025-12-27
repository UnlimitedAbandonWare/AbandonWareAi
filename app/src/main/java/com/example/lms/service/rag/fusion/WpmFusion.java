package com.example.lms.service.rag.fusion;

import java.util.Map;

/** Weighted Power Mean fuser; p=1 => arithmetic mean; p->+inf => max-like. */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: WpmFusion
 * 역할(Role): Class
 * 관련 기능(Tags): RAG Fusion
 * 소스 경로: app/src/main/java/com/example/lms/service/rag/fusion/WpmFusion.java
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
// Module: com.example.lms.service.rag.fusion.WpmFusion
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.example.lms.service.rag.fusion.WpmFusion
role: config
*/
class WpmFusion {
    private final double p;
    public WpmFusion(double p) { this.p = p == 0.0 ? 1.0 : p; }

    public double fuse(Map<String, Double> weights, Map<String, Double> scores) {
        if (weights == null || weights.isEmpty() || scores == null || scores.isEmpty()) return 0.0;
        double num = 0.0, den = 0.0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            String k = e.getKey();
            double w = Math.max(0.0, e.getValue() == null ? 0.0 : e.getValue());
            Double xs = scores.get(k);
            if (xs == null) continue;
            double x = Math.max(0.0, xs);
            num += w * Math.pow(x, p);
            den += w;
        }
        if (den == 0.0) return 0.0;
        return Math.pow(num / den, 1.0 / p);
    }
}