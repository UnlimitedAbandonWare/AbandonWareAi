// src/main/java/com/example/lms/service/rag/detector/GameDomainDetector.java
package com.example.lms.service.rag.detector;

import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 레거시용 게임 도메인 탐지기.
 *
 * <p>과거에는 이 클래스가 문자열 키워드만 보고
 * "GENSHIN" / "EDUCATION" / "GENERAL" 을 직접 판정했지만,
 * 이제는 {@link UniversalDomainDetector} 에 위임하는 얇은 호환 계층입니다.
 *
 * <p>새 코드에서는 UniversalDomainDetector 를 직접 사용하는 것을 권장합니다.
 */
@Component
@Deprecated // UniversalDomainDetector 사용 권장
@DependsOn("universalDomainDetector")
public class GameDomainDetector {

    private final UniversalDomainDetector universal;

    public GameDomainDetector(UniversalDomainDetector universal) {
        this.universal = universal;
    }

    /**
     * 문자열 기반 레거시 도메인 코드 반환.
     * <ul>
     *   <li>GENSHIN → "GENSHIN"</li>
     *   <li>IT_KNOWLEDGE / EDUCATION → "EDUCATION"</li>
     *   <li>그 외 → "GENERAL"</li>
     * </ul>
     */
    public String detect(String query) {
        String domain = universal.detect(query, null);
        if ("GENSHIN".equalsIgnoreCase(domain)) {
            return "GENSHIN";
        }
        if ("IT_KNOWLEDGE".equalsIgnoreCase(domain) || "EDUCATION".equalsIgnoreCase(domain)) {
            return "EDUCATION";
        }
        return "GENERAL";
    }
}
