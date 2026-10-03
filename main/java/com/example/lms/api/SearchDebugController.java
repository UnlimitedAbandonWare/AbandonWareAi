package com.example.lms.api;

import com.example.lms.gptsearch.decision.SearchDecision;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.routing.ApiRoutingPolicySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 에이전트용 검색 결정/런타임 JSON면. {@code /decision}은 {@link SearchDecisionService}
 * 판정만 돌려 보여주고 실제 검색은 절대 실행하지 않는다(유료 프로바이더 호출 없음).
 * {@code /runtime-status}는 기존 라우팅 정책({@link ApiRoutingPolicySnapshot})과
 * 프로바이더 자격증명 존재 여부({@link ProviderCredentialResolver}, 값은 절대
 * 노출하지 않고 소스 이름만)를 그대로 보고한다. 새 라우터가 아니라 읽기 전용
 * 진단면이다.
 */
@RestController
@RequestMapping("/api/internal/search")
@RequiredArgsConstructor
public class SearchDebugController {

    /** ProviderId 이름 -> ProviderCredentialResolver.Provider 매핑(해시 없으면 null). */
    private static final Map<ProviderId, ProviderCredentialResolver.Provider> CREDENTIAL_PROVIDER =
            Map.of(
                    ProviderId.NAVER, ProviderCredentialResolver.Provider.NAVER,
                    ProviderId.TAVILY, ProviderCredentialResolver.Provider.TAVILY,
                    ProviderId.SERPAPI, ProviderCredentialResolver.Provider.SERPAPI);

    private final SearchDecisionService decisionService;
    private final ApiRoutingPolicySnapshot routing;
    private final ProviderCredentialResolver credentialResolver;
    private final ObjectProvider<WebSearchProvider> webSearchProviders;
    private final Environment environment;

    /**
     * 검색 결정 드라이런. decide() 호출만 하고 WebSearchProvider.search는 호출하지 않는다.
     */
    @GetMapping("/decision")
    public ResponseEntity<Map<String, Object>> decision(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "providers", required = false) String providersCsv,
            @RequestParam(name = "topK", required = false) Integer topK,
            @RequestParam(name = "inferGeneral", required = false, defaultValue = "true")
            boolean inferGeneral) {
        SearchMode parsed;
        if (mode == null || mode.isBlank()) {
            parsed = SearchMode.AUTO;
        } else {
            try {
                parsed = SearchMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("error", "bad_mode");
                out.put("allowed", List.of("AUTO", "OFF", "FORCE_LIGHT", "FORCE_DEEP"));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(out);
            }
        }
        List<String> providerIds = new ArrayList<>();
        if (providersCsv != null) {
            for (String id : providersCsv.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) providerIds.add(trimmed);
            }
        }
        SearchDecision decision = decisionService.decide(
                q, parsed, providerIds.isEmpty() ? null : providerIds, topK, inferGeneral);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dryRun", true);
        out.put("executed", false);
        out.put("mode", parsed.name());
        out.put("shouldSearch", decision.shouldSearch());
        out.put("depth", decision.depth() == null ? null : decision.depth().name());
        List<String> providerNames = new ArrayList<>();
        for (ProviderId p : decision.providers()) providerNames.add(p.name());
        out.put("providers", providerNames);
        out.put("topK", decision.topK());
        out.put("reason", decision.reason());
        return ResponseEntity.ok(out);
    }

    /**
     * 검색 런타임 상태. 정책 문서(api-routing.yaml / agent-api-spend-guard.yaml),
     * 프로바이더 빈 인벤토리, 자격증명 존재 여부(이름만), 에이전트 모드/페이드
     * 오버라이드 플래그를 그대로 JSON으로 보고한다.
     */
    @GetMapping("/runtime-status")
    public ResponseEntity<Map<String, Object>> runtimeStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("chainEnabled",
                Boolean.parseBoolean(environment.getProperty("abandonware.chain.enabled", "true")));
        out.put("agentModeActive", routing.agentModeActive());
        out.put("explicitPaidOverride", routing.explicitPaidOverride());
        out.put("productionHardCaps", routing.productionHardCaps());
        out.put("maxClassifiedRetries", routing.maxClassifiedRetries());
        out.put("dedupeVerification", routing.dedupeVerification());
        out.put("policySources", routing.sourceHashes().keySet().stream().sorted().toList());

        Set<String> beanIds = new LinkedHashSet<>();
        webSearchProviders.orderedStream().forEach(p -> beanIds.add(p.id().name()));

        List<Map<String, Object>> routes = new ArrayList<>();
        for (ApiRoutingPolicySnapshot.Route route : routing.routes("search")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", route.id());
            row.put("tier", route.tier());
            row.put("modelPref", route.models());
            routes.add(row);
        }
        out.put("searchRoutes", routes);

        // ProviderId 빈 목록 + 정책 라우트 id의 합집합(예: brave는 ProviderId가 없지만 라우트에는 있음).
        Set<String> providerNames = new LinkedHashSet<>(beanIds);
        for (ApiRoutingPolicySnapshot.Route route : routing.routes("search")) {
            providerNames.add(route.id().toUpperCase(Locale.ROOT));
        }
        List<Map<String, Object>> providers = new ArrayList<>();
        for (String name : providerNames) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", name);
            row.put("bean", beanIds.contains(name));
            int tier = routing.tier("search", name.toLowerCase(Locale.ROOT));
            row.put("policyTier", tier == Integer.MAX_VALUE ? "unlisted" : routing.tierName(tier));
            ProviderCredentialResolver.Provider credentialProvider = credentialProvider(name);
            if (credentialProvider != null) {
                ProviderCredentialResolver.Resolution r = credentialResolver.resolve(credentialProvider);
                row.put("credentialPresent", r.credentialPresent());
                row.put("credentialSource", r.credentialPresent() ? r.sourceName() : null);
                row.put("providerEnabled", r.enabled());
                if (r.disabledReason() != null && !r.disabledReason().isBlank()) {
                    row.put("disabledReason", r.disabledReason());
                }
            } else {
                row.put("credentialPresent", null);
            }
            providers.add(row);
        }
        out.put("providers", providers);
        out.put("note", "dry-run/status surface only; no provider call is executed");
        return ResponseEntity.ok(out);
    }

    private static ProviderCredentialResolver.Provider credentialProvider(String providerIdName) {
        try {
            ProviderId id = ProviderId.valueOf(providerIdName.toUpperCase(Locale.ROOT));
            return CREDENTIAL_PROVIDER.get(id);
        } catch (IllegalArgumentException e) {
            // 정책 라우트 id(예: brave)는 ProviderId에 없을 수 있다 - resolver 매핑 시도.
            return switch (providerIdName.toLowerCase(Locale.ROOT)) {
                case "brave" -> ProviderCredentialResolver.Provider.BRAVE;
                default -> null;
            };
        }
    }
}
