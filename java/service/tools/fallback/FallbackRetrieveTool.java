// src/main/java/service/tools/fallback/FallbackRetrieveTool.java
package service.tools.fallback;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FallbackRetrieveTool {
    public List<Map<String, Object>> retrieveOrEmpty(String query, int topK, java.util.function.Supplier<List<Map<String, Object>>> primary) {
        try {
            List<Map<String,Object>> res = primary.get();
            return res == null ? List.of() : res;
        } catch (Exception ex) {
            // 로그는 상위 텔레메트리/트레이싱이 집계 (파이프라인 중단 금지)
            return List.of(); // 핵심: "실패-허용"으로 다음 단계 진행
        }
    }
}