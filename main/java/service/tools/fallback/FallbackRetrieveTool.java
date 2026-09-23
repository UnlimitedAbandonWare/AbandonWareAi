// src/main/java/service/tools/fallback/FallbackRetrieveTool.java
package service.tools.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FallbackRetrieveTool {
    private static final Logger log = LoggerFactory.getLogger(FallbackRetrieveTool.class);

    public List<Map<String, Object>> retrieveOrEmpty(String query, int topK, java.util.function.Supplier<List<Map<String, Object>>> primary) {
        try {
            List<Map<String,Object>> res = primary.get();
            return res == null ? List.of() : res;
        } catch (Exception ex) {
            logFailSoft("retrieveOrEmpty", ex);
            // 로그는 상위 텔레메트리/트레이싱이 집계 (파이프라인 중단 금지)
            return List.of(); // 핵심: "실패-허용"으로 다음 단계 진행
        }
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isDebugEnabled()) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.debug("[AWX][tools][fallback-retrieve] failSoft stage={} errorType={}", stage, errorType);
        }
    }
}
