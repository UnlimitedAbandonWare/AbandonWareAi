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
     * Emit a single-line JSON KPI record for each web fail-soft run (soak / log-based RCA).
     *
     * <p>Config key: {@code nova.orch.web-failsoft.emit-soak-kpi-json}</p>
     */
    private boolean emitSoakKpiJson = false;

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
     * Max ratio of NOFILTER_SAFE snippets allowed when official-only starvation fallback triggers.
     *
     * <p>Config key: {@code nova.orch.web-failsoft.official-only-starvation-fallback-max-ratio}</p>
     */
    // Default: allow a single NOFILTER_SAFE top-up when minCitations=2 and outCount=1.
    // (0.45 => floor(2*0.45)=0, blocks any top-up)
    private double officialOnlyStarvationFallbackMaxRatio = 0.55d;

    /**
     * Allowed intents for starvation fallback (empty list means deny all).
     *
     * <p>Config key: {@code nova.orch.web-failsoft.official-only-starvation-fallback-allowed-intents}</p>
     */
    private List<String> officialOnlyStarvationFallbackAllowedIntents = new ArrayList<>(List.of("GENERAL", "FINANCE", "TECH_API"));

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
    // Default: also trigger when we are below the minimum citation target.
    // This prevents "1 citation but still starved" cases from silently bypassing
    // the fail-soft top-up.
    private String officialOnlyStarvationFallbackTrigger = "BELOW_MIN_CITATIONS";


    // ---------------------------------------------------------------------
    // Guard: officialOnly starvation fallback quality gate
    // ---------------------------------------------------------------------

    /**
     * Enable the quality gate that prevents the officialOnly starvation fallback from returning
     * a citation set composed almost entirely of {@code UNVERIFIED} sources.
     *
     * <p>When triggered, the pipeline attempts to force-in at least one OFFICIAL/DOCS candidate
     * (best-effort). This is <b>observability-first</b>: it records TraceStore keys and may emit
     * a DebugEvent (when DebugEventStore is available).</p>
     */
    private boolean officialOnlyStarvationFallbackQualityGateEnabled = true;

    /**
     * Unverified ratio threshold that triggers the quality gate.
     *
     * <p>Example: 0.75 means "75% or more of selected snippets are UNVERIFIED".</p>
     */
    private double officialOnlyStarvationFallbackQualityGateUnverifiedRatioThreshold = 0.75;

    /**
     * If enabled, throw when the quality gate triggers but an OFFICIAL/DOCS snippet cannot be
     * force-inserted.
     *
     * <p>Default: false (fail-soft). Recommended only for tests/soak where you want to surface
     * misroutes early.</p>
     */
    private boolean officialOnlyStarvationFallbackQualityGateRequireForceInsert = false;


    /**
     * Default minimum citations (host-diverse evidence count) when the plan did not specify one.
     *
     * <p>Note: this only applies when GuardContext.minCitations is null (unset). An explicit 0 is respected.</p>
     */
    private int minCitationsDefault = 2;


    // ---------------------------------------------------------------------
    // Credibility + ordering policy (risk-control toggles)
    // ---------------------------------------------------------------------

    /**
     * Enable stage-based credibility boost when the authority scorer returns
     * UNVERIFIED but the domain/profile routing classifies the host as OFFICIAL/DOCS.
     *
     * <p>Default true: improves minCitations stability without waiting for the
     * authority list to be updated.</p>
     */
    private boolean stageBasedCredibilityBoostEnabled = true;

    /**
     * Mode for stage-based credibility boost.
     *
     * <ul>
     *   <li><b>AGGRESSIVE</b>: OFFICIAL->OFFICIAL, DOCS->TRUSTED</li>
     *   <li><b>CONSERVATIVE</b>: OFFICIAL->TRUSTED, DOCS->TRUSTED</li>
     * </ul>
     *
     * <p>Default CONSERVATIVE: satisfies minCitations while avoiding over-claiming
     * OFFICIAL credibility when the scorer is missing entries.</p>
     */
    private String stageBasedCredibilityBoostMode = "CONSERVATIVE";

    /**
     * CiteableTopUp insertion strategy.
     *
     * <ul>
     *   <li><b>HEAD</b>: always insert at index 0 (may reverse insertion order)</li>
     *   <li><b>HEAD_STABLE</b>: insert at a growing head cursor (stable insertion order)</li>
     *   <li><b>PREFIX_STABLE</b>: insert after existing citeable prefix (default)</li>
     * </ul>
     */
    private String citeableTopUpInsertMode = "PREFIX_STABLE";


    /**
     * If strict reordering/filtering yields nothing, allow a small number of extra searches
     * using deterministic augmented queries (no LLM needed).
     */
    private boolean allowExtraSearchCalls = true;
    private int maxExtraSearchCalls = 2;


    /**
     * Templates for "OFFICIAL/DOCS rescue" queries.
     *
     * <p>
     * Used by deterministic extra search calls (when {@link #allowExtraSearchCalls} is enabled),
     * and by the officialOnly starvation fallback quality gate rescue path.
     * </p>
     *
     * <p>
     * Placeholders:
     * <ul>
     *   <li><code>{canonical}</code> - canonical query</li>
     *   <li><code>{entity}</code> - best-effort leading entity</li>
     * </ul>
     * </p>
     *
     * <p>Config key: {@code nova.orch.web-failsoft.official-docs-rescue-queries}</p>
     */
    private List<String> officialDocsRescueQueries = new ArrayList<>(List.of(
            "{entity} 공식 홈페이지",
            "{entity} 공식 사이트",
            "{canonical} 공식 문서",
            "{canonical} site:github.com {entity}",
            "{entity} 공식 site:twitch.tv",
            "{entity} 공식 site:wikipedia.org",
            "{entity} official site",
            "{canonical} official docs"
    ));

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


    public boolean isEmitSoakKpiJson() {
        return emitSoakKpiJson;
    }

    public void setEmitSoakKpiJson(boolean emitSoakKpiJson) {
        this.emitSoakKpiJson = emitSoakKpiJson;
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


    public double getOfficialOnlyStarvationFallbackMaxRatio() {
        return officialOnlyStarvationFallbackMaxRatio;
    }

    public void setOfficialOnlyStarvationFallbackMaxRatio(double officialOnlyStarvationFallbackMaxRatio) {
        this.officialOnlyStarvationFallbackMaxRatio = officialOnlyStarvationFallbackMaxRatio;
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

    public boolean isOfficialOnlyStarvationFallbackQualityGateEnabled() {
        return officialOnlyStarvationFallbackQualityGateEnabled;
    }

    public void setOfficialOnlyStarvationFallbackQualityGateEnabled(boolean officialOnlyStarvationFallbackQualityGateEnabled) {
        this.officialOnlyStarvationFallbackQualityGateEnabled = officialOnlyStarvationFallbackQualityGateEnabled;
    }

    public double getOfficialOnlyStarvationFallbackQualityGateUnverifiedRatioThreshold() {
        return officialOnlyStarvationFallbackQualityGateUnverifiedRatioThreshold;
    }

    public void setOfficialOnlyStarvationFallbackQualityGateUnverifiedRatioThreshold(double officialOnlyStarvationFallbackQualityGateUnverifiedRatioThreshold) {
        this.officialOnlyStarvationFallbackQualityGateUnverifiedRatioThreshold = officialOnlyStarvationFallbackQualityGateUnverifiedRatioThreshold;
    }

    public boolean isOfficialOnlyStarvationFallbackQualityGateRequireForceInsert() {
        return officialOnlyStarvationFallbackQualityGateRequireForceInsert;
    }

    public void setOfficialOnlyStarvationFallbackQualityGateRequireForceInsert(boolean officialOnlyStarvationFallbackQualityGateRequireForceInsert) {
        this.officialOnlyStarvationFallbackQualityGateRequireForceInsert = officialOnlyStarvationFallbackQualityGateRequireForceInsert;
    }

    public int getMinCitationsDefault() {
        return minCitationsDefault;
    }

    public void setMinCitationsDefault(int minCitationsDefault) {
        this.minCitationsDefault = Math.max(0, minCitationsDefault);
    }


    public boolean isStageBasedCredibilityBoostEnabled() {
        return stageBasedCredibilityBoostEnabled;
    }

    public void setStageBasedCredibilityBoostEnabled(boolean stageBasedCredibilityBoostEnabled) {
        this.stageBasedCredibilityBoostEnabled = stageBasedCredibilityBoostEnabled;
    }

    public String getStageBasedCredibilityBoostMode() {
        return stageBasedCredibilityBoostMode;
    }

    public void setStageBasedCredibilityBoostMode(String stageBasedCredibilityBoostMode) {
        this.stageBasedCredibilityBoostMode = stageBasedCredibilityBoostMode;
    }

    public String getCiteableTopUpInsertMode() {
        return citeableTopUpInsertMode;
    }

    public void setCiteableTopUpInsertMode(String citeableTopUpInsertMode) {
        this.citeableTopUpInsertMode = citeableTopUpInsertMode;
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

    public List<String> getOfficialDocsRescueQueries() {
        return officialDocsRescueQueries;
    }

    public void setOfficialDocsRescueQueries(List<String> officialDocsRescueQueries) {
        this.officialDocsRescueQueries =
                (officialDocsRescueQueries == null) ? new ArrayList<>() : officialDocsRescueQueries;
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
