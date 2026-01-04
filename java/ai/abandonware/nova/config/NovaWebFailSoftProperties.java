package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web fail-soft configuration.
 *
 * <p>This is an additive, opt-in (but enabled by default) safety net to keep
 * web evidence selection stable when LLM helpers are degraded (breaker-open)
 * and the query becomes ambiguous (e.g., "잼미나이 한도" drifting to finance).
 */
@Validated
@ConfigurationProperties(prefix = "nova.orch.web-failsoft")
public class NovaWebFailSoftProperties {

    /** Master toggle. */
    private boolean enabled = true;

    /**
     * Fail-soft relaxation order for WEB evidence selection.
     * Default: OFFICIAL -> DOCS -> DEV_COMMUNITY -> PROFILEBOOST -> NOFILTER_SAFE
     */
    private List<String> stageOrder = new ArrayList<>(List.of(
            "OFFICIAL",
            "DOCS",
            "DEV_COMMUNITY",
            "PROFILEBOOST",
            "NOFILTER_SAFE"
    ));

    /**
     * Prefix snippet body with stage/credibility tags so that trace and prompt builders
     * can keep this information even when metadata is dropped.
     */
    private boolean tagSnippetBody = true;

    /**
     * Record per-request (host, stage, credibility) tuples into TraceStore so that
     * EOR/FIND_LOG 기반으로 반복되는 도메인/스테이지 조합 패턴을 쉽게 역추적할 수 있다.
     */
    private boolean traceDomainStagePairs = true;

    /** Maximum number of raw domain/stage pairs to record per request (to keep traces small). */
    private int maxTraceDomainStagePairs = 30;

    /**
     * Allow an explicit NOFILTER stage (true no-filter) at the very end.
     *
     * <p>Default false: keeps NOFILTER separated and disabled unless explicitly enabled.
     * When enabled and stageOrder includes NOFILTER, TECH spam items may be placed into
     * the NOFILTER bucket as a last resort.
     */
    private boolean allowNoFilterStage = false;

    /**
     * When {@code officialOnly=true} clamps the effective stage order, decide whether
     * {@link ai.abandonware.nova.orch.web.WebFailSoftStage#DEV_COMMUNITY} is still allowed.
     *
     * <p>Default: true (preserve existing behavior)</p>
     *
     * <p>Config key: {@code nova.orch.web-failsoft.official-only-include-dev-community}</p>
     */
    private boolean officialOnlyIncludeDevCommunity = true;

    /**
     * Starvation escape hatch for {@code officialOnly=true} clamp.
     *
     * <p>When enabled, and the official-only clamp produces too few citations, we may
     * top-up from {@link ai.abandonware.nova.orch.web.WebFailSoftStage#NOFILTER_SAFE}.</p>
     *
     * <p>Config key: {@code nova.orch.web-failsoft.official-only-starvation-fallback-enabled}</p>
     */
    private boolean officialOnlyStarvationFallbackEnabled = true;

    /**
     * Maximum number of NOFILTER_SAFE snippets to add when starvation fallback triggers.
     *
     * <p>Config key: {@code nova.orch.web-failsoft.official-only-starvation-fallback-max}</p>
     */
    private int officialOnlyStarvationFallbackMax = 3;

    /**
     * Allowed intents for starvation fallback (empty list means deny all).
     *
     * <p>Config key: {@code nova.orch.web-failsoft.official-only-starvation-fallback-allowed-intents}</p>
     */
    private List<String> officialOnlyStarvationFallbackAllowedIntents = new ArrayList<>(List.of("GENERAL", "TECH_API"));

    /**
     * Trigger mode for starvation fallback.
     * Supported values:
     * <ul>
     *     <li>{@code EMPTY_ONLY}</li>
     *     <li>{@code BELOW_MIN_CITATIONS}</li>
     * </ul>
     *
     * <p>Config key: {@code nova.orch.web-failsoft.official-only-starvation-fallback-trigger}</p>
     */
    private String officialOnlyStarvationFallbackTrigger = "EMPTY_ONLY";


    /**
     * Default minimum citations (host-diverse evidence count) when the plan did not specify one.
     *
     * <p>Note: this only applies when GuardContext.minCitations is null (unset). An explicit 0 is respected.</p>
     */
    private int minCitationsDefault = 2;


    /**
     * If strict reordering/filtering yields nothing, allow a small number of extra searches
     * using deterministic augmented queries (no LLM needed).
     */
    private boolean allowExtraSearchCalls = true;
    private int maxExtraSearchCalls = 1;

    /** Alias replacements that should work even when QueryTransformer/LLM helper is down. */
    private Map<String, String> aliasMap = new LinkedHashMap<>();

    /** Keywords that indicate "loan/finance spam" for TECH_API intent. */
    private List<String> techSpamKeywords = new ArrayList<>(List.of(
            "대출", "금리", "신용", "카드", "한도조회", "개인사업자", "대부", "담보", "상환",
            "연체", "이자", "캐피탈", "보험"
    ));

    /** Optional: domains to drop for TECH_API intent regardless of stage. */
    private List<String> techSpamDomains = new ArrayList<>();

    /** OFFICIAL domains (vendor-owned docs / authoritative sources). */
    private List<String> officialDomains = new ArrayList<>(List.of(
            "ai.google.dev",
            "developers.google.com",
            "cloud.google.com",
            "support.google.com",
            "openai.com",
            "platform.openai.com",
            "help.openai.com",
            "docs.anthropic.com"
    ));

    /** DOCS domains (documentation-first sites). */
    private List<String> docsDomains = new ArrayList<>(List.of(
            "developer.mozilla.org",
            "docs.oracle.com",
            "javadoc.io",
            "readthedocs.io",
            "pkg.go.dev",
            "docs.rs",
            "learn.microsoft.com"
    ));

    /** DEV COMMUNITY domains (Q&A / issue trackers / forums). */
    private List<String> devCommunityDomains = new ArrayList<>(List.of(
            "stackoverflow.com",
            "stackexchange.com",
            "github.com",
            "superuser.com",
            "serverfault.com"
    ));



    /**
     * Domains that should NEVER be treated as DEV_COMMUNITY even if they match the devCommunityDomains suffix list.
     *
     * <p>This is used to quickly "reconnect" misrouted hosts discovered via
     * {@code web.failsoft.domainStagePairs} (e.g., broad suffixes accidentally matching portal/UGC hosts).</p>
     */
    private List<String> devCommunityDenyDomains = new ArrayList<>();
    public NovaWebFailSoftProperties() {
        // Safe defaults: common KR misspellings -> canonical vendor term
        // (Spring Binder will override if user config provides aliasMap).
        aliasMap.put("잼미나이", "Gemini");
        aliasMap.put("제미나이", "Gemini");
        aliasMap.put("제미니", "Gemini");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getStageOrder() {
        return stageOrder;
    }

    public void setStageOrder(List<String> stageOrder) {
        this.stageOrder = stageOrder;
    }

    public boolean isTagSnippetBody() {
        return tagSnippetBody;
    }

    public void setTagSnippetBody(boolean tagSnippetBody) {
        this.tagSnippetBody = tagSnippetBody;
    }

    public boolean isTraceDomainStagePairs() {
        return traceDomainStagePairs;
    }

    public void setTraceDomainStagePairs(boolean traceDomainStagePairs) {
        this.traceDomainStagePairs = traceDomainStagePairs;
    }

    public int getMaxTraceDomainStagePairs() {
        return maxTraceDomainStagePairs;
    }

    public void setMaxTraceDomainStagePairs(int maxTraceDomainStagePairs) {
        this.maxTraceDomainStagePairs = maxTraceDomainStagePairs;
    }


    public boolean isAllowNoFilterStage() {
        return allowNoFilterStage;
    }

    public void setAllowNoFilterStage(boolean allowNoFilterStage) {
        this.allowNoFilterStage = allowNoFilterStage;
    }

    public boolean isOfficialOnlyIncludeDevCommunity() {
        return officialOnlyIncludeDevCommunity;
    }

    public void setOfficialOnlyIncludeDevCommunity(boolean officialOnlyIncludeDevCommunity) {
        this.officialOnlyIncludeDevCommunity = officialOnlyIncludeDevCommunity;
    }

    public boolean isOfficialOnlyStarvationFallbackEnabled() {
        return officialOnlyStarvationFallbackEnabled;
    }

    public void setOfficialOnlyStarvationFallbackEnabled(boolean officialOnlyStarvationFallbackEnabled) {
        this.officialOnlyStarvationFallbackEnabled = officialOnlyStarvationFallbackEnabled;
    }

    public int getOfficialOnlyStarvationFallbackMax() {
        return officialOnlyStarvationFallbackMax;
    }

    public void setOfficialOnlyStarvationFallbackMax(int officialOnlyStarvationFallbackMax) {
        this.officialOnlyStarvationFallbackMax = officialOnlyStarvationFallbackMax;
    }

    public List<String> getOfficialOnlyStarvationFallbackAllowedIntents() {
        return officialOnlyStarvationFallbackAllowedIntents;
    }

    public void setOfficialOnlyStarvationFallbackAllowedIntents(List<String> officialOnlyStarvationFallbackAllowedIntents) {
        this.officialOnlyStarvationFallbackAllowedIntents =
                (officialOnlyStarvationFallbackAllowedIntents == null) ? new ArrayList<>() : officialOnlyStarvationFallbackAllowedIntents;
    }

    public String getOfficialOnlyStarvationFallbackTrigger() {
        return officialOnlyStarvationFallbackTrigger;
    }

    public void setOfficialOnlyStarvationFallbackTrigger(String officialOnlyStarvationFallbackTrigger) {
        this.officialOnlyStarvationFallbackTrigger = officialOnlyStarvationFallbackTrigger;
    }

    public int getMinCitationsDefault() {
        return minCitationsDefault;
    }

    public void setMinCitationsDefault(int minCitationsDefault) {
        this.minCitationsDefault = Math.max(0, minCitationsDefault);
    }

    public boolean isAllowExtraSearchCalls() {
        return allowExtraSearchCalls;
    }

    public void setAllowExtraSearchCalls(boolean allowExtraSearchCalls) {
        this.allowExtraSearchCalls = allowExtraSearchCalls;
    }

    public int getMaxExtraSearchCalls() {
        return maxExtraSearchCalls;
    }

    public void setMaxExtraSearchCalls(int maxExtraSearchCalls) {
        this.maxExtraSearchCalls = maxExtraSearchCalls;
    }

    public Map<String, String> getAliasMap() {
        return aliasMap;
    }

    public void setAliasMap(Map<String, String> aliasMap) {
        this.aliasMap = (aliasMap == null) ? new LinkedHashMap<>() : aliasMap;
    }

    public List<String> getTechSpamKeywords() {
        return techSpamKeywords;
    }

    public void setTechSpamKeywords(List<String> techSpamKeywords) {
        this.techSpamKeywords = techSpamKeywords;
    }

    public List<String> getTechSpamDomains() {
        return techSpamDomains;
    }

    public void setTechSpamDomains(List<String> techSpamDomains) {
        this.techSpamDomains = techSpamDomains;
    }

    public List<String> getOfficialDomains() {
        return officialDomains;
    }

    public void setOfficialDomains(List<String> officialDomains) {
        this.officialDomains = officialDomains;
    }

    public List<String> getDocsDomains() {
        return docsDomains;
    }

    public void setDocsDomains(List<String> docsDomains) {
        this.docsDomains = docsDomains;
    }

    public List<String> getDevCommunityDomains() {
        return devCommunityDomains;
    }

    public void setDevCommunityDomains(List<String> devCommunityDomains) {
        this.devCommunityDomains = devCommunityDomains;
    }

    public List<String> getDevCommunityDenyDomains() {
        return devCommunityDenyDomains;
    }

    public void setDevCommunityDenyDomains(List<String> devCommunityDenyDomains) {
        this.devCommunityDenyDomains = devCommunityDenyDomains;
    }

}
