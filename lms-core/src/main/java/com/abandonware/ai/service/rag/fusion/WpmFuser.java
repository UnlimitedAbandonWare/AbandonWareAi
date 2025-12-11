package com.abandonware.ai.service.rag.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Weighted Power Mean fuser.
 * p->infinity => max, p=1 => arithmetic mean, p->0 => geometric mean.
 * Compile-safe utility; no external deps.
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: WpmFuser
 * 역할(Role): Class
 * 관련 기능(Tags): RAG Fusion
 * 소스 경로: lms-core/src/main/java/com/abandonware/ai/service/rag/fusion/WpmFuser.java
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
// Module: com.abandonware.ai.service.rag.fusion.WpmFuser
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.WpmFuser
role: config
*/
class WpmFuser {

    public double wpm(List<Double> xs, List<Double> ws, double p){
        if (xs == null || xs.isEmpty()) return 0.0d;
        if (ws == null || ws.size() != xs.size()) {
            ws = new ArrayList<>(Collections.nCopies(xs.size(), 1.0d));
        }
        double num = 0.0d, den = 0.0d;
        for (int i = 0; i < xs.size(); i++) {
            Double xv = xs.get(i);
            Double wv = ws.get(i);
            double x = Math.max(xv == null ? 0.0d : xv.doubleValue(), 1e-9);
            double w = wv == null ? 1.0d : wv.doubleValue();
            num += w * Math.pow(x, p);
            den += w;
        }
        if (den == 0.0d) return 0.0d;
        return Math.pow(num / den, 1.0d / p);
    }
}