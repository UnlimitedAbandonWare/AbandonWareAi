
package com.abandonware.ai.agent.fallback;
import com.abandonware.ai.agent.rag.model.Result;
import java.util.*;

public class NovaFallbackCoordinator {
    public String handle(String query, List<Result> results) {
        // Minimal safe message
        return "정보 부족: 신뢰 가능한 근거를 더 수집 중입니다.";
    }
}
