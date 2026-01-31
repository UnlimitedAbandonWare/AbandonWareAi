package com.abandonware.ai.addons.calibration;

import java.util.Map;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: WeightedPowerMean
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/addons/calibration/WeightedPowerMean.java
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
// Module: com.abandonware.ai.addons.calibration.WeightedPowerMean
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.addons.calibration.WeightedPowerMean
role: config
*/
class WeightedPowerMean {
    private final double p;
    public WeightedPowerMean(double p) { this.p = p; }

    public double aggregate(Map<String, Double> probs, Map<String, Double> weights) {
        if (probs == null || probs.isEmpty()) return 0d;
        double num = 0d, den = 0d;
        for (Map.Entry<String, Double> e : probs.entrySet()) {
            final String s = e.getKey();
            final double w = (weights == null ? 1d : weights.getOrDefault(s, 1d));
            final double v = clamp(e.getValue());
            num += w * Math.pow(v, p == 0 ? 1e-9 : p);
            den += w;
        }
        double mean = (den == 0 ? 0d : num / den);
        if (p == 0) { // geometric limit
            double logSum = 0d, wSum = 0d;
            for (Map.Entry<String, Double> e : probs.entrySet()) {
                final String s = e.getKey();
                final double w = (weights == null ? 1d : weights.getOrDefault(s, 1d));
                logSum += w * Math.log(clamp(e.getValue()) + 1e-12);
                wSum += w;
            }
            return Math.exp(logSum / Math.max(1e-9, wSum));
        }
        return Math.pow(mean, 1.0 / p);
    }

    private static double clamp(double x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }
}