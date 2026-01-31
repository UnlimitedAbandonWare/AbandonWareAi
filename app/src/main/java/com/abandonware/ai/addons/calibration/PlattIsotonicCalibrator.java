package com.abandonware.ai.addons.calibration;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: PlattIsotonicCalibrator
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/addons/calibration/PlattIsotonicCalibrator.java
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
// Module: com.abandonware.ai.addons.calibration.PlattIsotonicCalibrator
// Role: config
// Feature Flags: kg
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.addons.calibration.PlattIsotonicCalibrator
role: config
flags: [kg]
*/
class PlattIsotonicCalibrator implements ScoreCalibrator {
    public static final class Platt { public final double a,b; public Platt(double a,double b){this.a=a;this.b=b;} }
    public interface IsoFn { double apply(double z); }

    private final Map<String, Platt> pl = new HashMap<>();
    private final Map<String, IsoFn> iso = new HashMap<>();
    private final RobustStatsProvider stats = new RobustStatsProvider();

    public PlattIsotonicCalibrator() {
        // defaults; can be refreshed from KV/props later
        pl.put("web", new Platt(1.0, 0.0));
        pl.put("vec", new Platt(1.0, 0.0));
        pl.put("kg",  new Platt(1.2, -0.1));
        iso.put("web", z -> sigmoid(z));
        iso.put("vec", z -> sigmoid(z));
        iso.put("kg",  z -> sigmoid(1.1*z));
        stats.with("web", 0.1, 0.5, 0.9).with("vec", 0.1, 0.5, 0.9).with("kg", 0.05, 0.5, 0.95);
    }

    @Override
    public double calibrate(String source, double raw, CalibContext ctx) {
        RobustStatsProvider.RobustStats s = stats.of(source);
        double z = (raw - s.p50) / (Math.max(1e-6, (s.p90 - s.p10)));
        Platt pp = pl.getOrDefault(source, new Platt(1.0, 0.0));
        double pPlatt = sigmoid(pp.a * z + pp.b);
        double pIso   = iso.getOrDefault(source, (zz)->sigmoid(zz)).apply(z);
        double pHat = 0.5*(pPlatt + pIso);
        // light MP-Law-style rescale
        double gamma = 1.2, lambda = 0.8;
        double coverage = (ctx == null ? 0.0 : ctx.coverage);
        double mp = Math.pow(pHat, gamma) * (1.0 - Math.exp(-lambda * coverage));
        return clamp(mp);
    }

    private static double sigmoid(double x) { return 1.0 / (1.0 + Math.exp(-x)); }
    private static double clamp(double x) { return x<0?0:(x>1?1:x); }
}