package com.abandonware.ai.addons.fusion;

import com.abandonware.ai.addons.calibration.ScoreCalibrator;
import com.abandonware.ai.addons.calibration.ScoreCalibrator.CalibContext;
import com.abandonware.ai.addons.calibration.WeightedPowerMean;
import com.abandonware.ai.addons.model.ContextSlice;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;

@Component
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: CalibratedRrfFusion
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/addons/fusion/CalibratedRrfFusion.java
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
// Module: com.abandonware.ai.addons.fusion.CalibratedRrfFusion
// Role: config
// Feature Flags: telemetry
// Dependencies: com.abandonware.ai.addons.calibration.ScoreCalibrator, com.abandonware.ai.addons.calibration.ScoreCalibrator.CalibContext, com.abandonware.ai.addons.calibration.WeightedPowerMean, +1 more
// Observability: propagates trace headers if present.
// Thread-Safety: unknown.
// /
/* agent-hint:
id: com.abandonware.ai.addons.fusion.CalibratedRrfFusion
role: config
flags: [telemetry]
*/
class CalibratedRrfFusion {

    @Value("${calibration.wpm.p:0.3}")
    private double p;

    @Value("${calibration.wpm.alpha_rrf_blend:0.6}")
    private double alpha;

    @Value("${retrieval.fusion.rrf.k:60}")
    private int k;

    public static final class Fused {
        public final List<ContextSlice> items;
        public final Map<String, Object> telemetry;
        public Fused(List<ContextSlice> items, Map<String, Object> telemetry) {
            this.items = items; this.telemetry = telemetry;
        }
    }

    private final ScoreCalibrator calibrator;
    public CalibratedRrfFusion(ScoreCalibrator calibrator) { this.calibrator = calibrator; }

    public Fused fuse(Map<String, List<ContextSlice>> perSource, double coverage) {
        // 1) normalize per source
        Map<String, List<ContextSlice>> norm = new LinkedHashMap<>();
        Map<String, Double> probsForWpm = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<String, List<ContextSlice>> e : perSource.entrySet()) {
            String s = e.getKey();
            List<ContextSlice> xs = e.getValue();
            List<ContextSlice> out = new ArrayList<>();
            int rank = 1;
            for (ContextSlice c : xs) {
                double pHat = calibrator.calibrate(s, c.score, new CalibContext(coverage));
                double rrf = 1.0 / (k + rank);
                double fused = alpha * rrf + (1 - alpha) * pHat;
                ContextSlice y = new ContextSlice(c.id, c.title, c.snippet, (c.source==null?s:c.source), fused, rank);
                out.add(y);
                rank++;
            }
            norm.put(s, out);
            // aggregate head probability for WPM
            probsForWpm.put(s, out.isEmpty() ? 0.0 : out.get(0).score);
            weights.put(s, 1.0);
        }

        // 2) WPM combine the heads for tie-breaking influence
        WeightedPowerMean wpm = new WeightedPowerMean(p);
        double wpmScore = wpm.aggregate(probsForWpm, weights);

        // 3) RRF merge
        List<ContextSlice> merged = new ArrayList<>();
        Map<String, ContextSlice> bestById = new LinkedHashMap<>();
        for (List<ContextSlice> xs : norm.values()) {
            int rank = 1;
            for (ContextSlice c : xs) {
                double rrf = 1.0 / (k + rank);
                String key = c.id == null ? (c.source + "#" + rank) : c.id;
                ContextSlice acc = bestById.get(key);
                double score = c.score + wpmScore * 0.05; // light boost
                if (acc == null) {
                    bestById.put(key, new ContextSlice(c.id, c.title, c.snippet, c.source, score, 0));
                } else {
                    acc.score += score;
                }
                rank++;
            }
        }

        List<ContextSlice> items = new ArrayList<>(bestById.values());
        items.sort(Comparator.comparingDouble((ContextSlice c) -> c.score).reversed());
        int r=1; for (ContextSlice c: items) c.rank = r++;

        Map<String,Object> tel = new LinkedHashMap<>();
        tel.put("alpha_rrf", alpha);
        tel.put("p_wpm", p);
        tel.put("wpm_score", wpmScore);
        return new Fused(items, tel);
    }
}