package com.example.lms.service.rag.guard;

import com.example.lms.guard.AnswerSanitizer;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.rag.knowledge.UniversalLoreRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 모든 도메인(Genshin 포함)의 금칙어/톤을 관리하는 통합 가드.
 *
 * <p>현재 단계에서는 {@link UniversalLoreRegistry}를 통해 도메인별 정보를 조회하고
 * 필요한 경우 후속 규칙을 적용할 수 있도록 훅(hook)을 제공한다.
 * 아직 forbidden terms / safety rules 가 정의되지 않은 상태이므로,
 * 기본 구현은 입력 answer 를 그대로 반환하면서 도메인/프로파일 로그만 남긴다.</p>
 *
 * <p>기존 {@code GenshinRecommendationSanitizer} 는 이 클래스를 delegate 로 사용하도록
 * 얇은 wrapper (@Deprecated) 로 전환된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniversalDomainSanitizer implements AnswerSanitizer {

    private final UniversalLoreRegistry loreRegistry;

    @Override
    public String sanitize(String answer, PromptContext ctx) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }

        String domain = null;
        if (ctx != null) {
            if (ctx.domain() != null && !ctx.domain().isBlank()) {
                domain = ctx.domain();
            } else if (ctx.queryDomain() != null) {
                domain = ctx.queryDomain().name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        if (domain == null || domain.isBlank()) {
            // 도메인 정보가 없으면 수정하지 않고 그대로 반환
            return answer;
        }

        try {
            List<UniversalLoreRegistry.DomainKnowledge> lore = loreRegistry.findLore(List.of(domain));
            if (!lore.isEmpty()) {
                log.debug("[UniversalDomainSanitizer] domain={} loreHits={}", domain, lore.size());
            }
            // TODO: 추후 forbiddenTerms / safetyRules 정보를 DomainKnowledge 에 추가하여
            // 특정 키워드 마스킹, 톤 조정 등을 수행할 수 있다.
        } catch (Exception e) {
            log.debug("[UniversalDomainSanitizer] lore lookup failed for domain={}", domain, e);
        }

        return answer;
    }
}
