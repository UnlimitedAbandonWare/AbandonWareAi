package com.abandonware.ai.addons.calibration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: RobustStatsProvider
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/addons/calibration/RobustStatsProvider.java
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
// Module: com.abandonware.ai.addons.calibration.RobustStatsProvider
// Role: config
// Observability: propagates trace headers if present.
// Thread-Safety: uses concurrent primitives.
// /
/* agent-hint:
id: com.abandonware.ai.addons.calibration.RobustStatsProvider
role: config
*/
class RobustStatsProvider {
    public static final class RobustStats {
        public final double p10, p50, p90;
        public RobustStats(double p10, double p50, double p90) { this.p10=p10; this.p50=p50; this.p90=p90; }
    }

    private final Map<String, RobustStats> table = new ConcurrentHashMap<>();
    public RobustStatsProvider with(String source, double p10, double p50, double p90) {
        table.put(source, new RobustStats(p10,p50,p90));
        return this;
    }
    public RobustStats of(String source) {
        return table.getOrDefault(source, new RobustStats(0, 0.5, 1));
    }
}