package com.abandonware.ai.agent.orchestrator;

import java.util.*;
import com.abandonware.ai.util.SoftmaxUtil;
import com.abandonware.ai.strategy.KAllocationPolicy;
import com.abandonware.ai.addons.complexity.QueryComplexityClassifier;
import com.abandonware.ai.addons.complexity.ComplexityResult;
import com.abandonware.ai.addons.complexity.ComplexityTag;

/** Runtime advisor to compute {web,vector,kg} K split from query complexity/intent. */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: KAllocRuntime
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/agent/orchestrator/KAllocRuntime.java
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
// Module: com.abandonware.ai.agent.orchestrator.KAllocRuntime
// Role: config
// Feature Flags: kg
// Dependencies: com.abandonware.ai.util.SoftmaxUtil, com.abandonware.ai.strategy.KAllocationPolicy, com.abandonware.ai.addons.complexity.QueryComplexityClassifier, +2 more
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.agent.orchestrator.KAllocRuntime
role: config
flags: [kg]
*/
class KAllocRuntime {
    private KAllocRuntime(){}
    private static final Set<String> OCR_HINT = Set.of("ocr","스캔","이미지","사진","table","표","도표","scan","pdf");
    private static final Set<String> NEWS_HINT = Set.of("news","최신","today","release","update","릴리즈","업데이트","가격","시세","환율");

    public static Map<String,Integer> compute(String query, int baseK, double temperature, String intent){
        if (baseK <= 0) baseK = 24;
        if (temperature <= 0) temperature = 0.7;
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        QueryComplexityClassifier clf = new QueryComplexityClassifier();
        ComplexityResult cr = clf.classify(q);

        double zWeb = 0.0, zVec = 0.0, zKg = -0.2;
        // Complexity cues
        if (cr.tag() == ComplexityTag.WEB_REQUIRED) zWeb += 0.6;
        else if (cr.tag() == ComplexityTag.COMPLEX) { zVec += 0.2; }
        else if (cr.tag() == ComplexityTag.DOMAIN_SPECIFIC) { zVec += 0.3; }
        // Intent/keyword cues
        if (intent != null) {
            String it = intent.toLowerCase(Locale.ROOT);
            if (it.contains("news") || it.contains("web") || it.contains("최신")) zWeb += 0.4;
            if (it.contains("kg") || it.contains("graph")) zKg += 0.2;
        }
        for(String t: NEWS_HINT) if(q.contains(t)) { zWeb += 0.3; break; }
        for(String t: OCR_HINT) if(q.contains(t)) { zKg -= 0.2; zVec += 0.1; } // prefer vec for text-like OCR handoff

        // Normalize with Softmax policy
        KAllocationPolicy pol = new KAllocationPolicy(temperature);
        Map<String,Integer> m = pol.recommendK(baseK, zWeb, zVec, zKg);
        // Ensure minimums
        if (m.get("web") == 0 && cr.tag() == ComplexityTag.WEB_REQUIRED) m.put("web", Math.max(2, (int)Math.round(baseK*0.3)));
        if (m.get("vector") == 0) m.put("vector", 1);
        int sum = m.get("web") + m.get("vector") + m.get("kg");
        if (sum < baseK) m.put("vector", m.get("vector") + (baseK - sum));
        return m;
    }
}