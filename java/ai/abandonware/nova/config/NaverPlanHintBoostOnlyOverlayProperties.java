package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tuning knobs for {@link ai.abandonware.nova.orch.aop.NaverPlanHintBoostOnlyOverlayAspect}.
 *
 * <p>
 * Purpose: reduce safe.v1(strict)로 인한 Naver web starvation을 완화하기 위해,
 * Naver 호출 구간에서만 plan-hint를 "boost-only"로 완화하는 Overlay를 적용한다.
 * 단, 의료/위치/민감 주제는 <b>완화 금지</b>이므로 이 Properties로 감지 룰을 튜닝한다.
 */
@Validated
@ConfigurationProperties(prefix = "nova.orch.hatch.naver-planhint-boost-only")
public class NaverPlanHintBoostOnlyOverlayProperties {

    /** Master toggle (kept in-sync with ConditionalOnProperty in AutoConfiguration). */
    private boolean enabled = true;

    /** Apply overlay only when the inbound GuardContext is strict (officialOnly or domainProfile is set). */
    private boolean applyOnlyWhenPlanHintStrict = true;

    private final Medical medical = new Medical();
    private final Location location = new Location();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isApplyOnlyWhenPlanHintStrict() {
        return applyOnlyWhenPlanHintStrict;
    }

    public void setApplyOnlyWhenPlanHintStrict(boolean applyOnlyWhenPlanHintStrict) {
        this.applyOnlyWhenPlanHintStrict = applyOnlyWhenPlanHintStrict;
    }

    public Medical getMedical() {
        return medical;
    }

    public Location getLocation() {
        return location;
    }


    // ---------------------------------------------------------------------
    // Nested groups
    // ---------------------------------------------------------------------

    public static class Medical {
        /**
         * Keywords that should be treated as "medical intent" and therefore block boost-only overlay.
         */
        private List<String> keywords = new ArrayList<>(List.of(
                "병원", "의료", "의사", "진료", "처방", "약", "약국", "응급", "질병", "증상", "치료", "부작용",
                "medical", "hospital", "clinic", "doctor", "prescription", "pharmacy"
        ));

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = (keywords == null) ? new ArrayList<>() : keywords;
        }
    }

    public static class Location {
        /**
         * Primary location keywords (hit => treat as local intent and block boost-only overlay).
         */
        private List<String> keywords = new ArrayList<>(List.of(
                "근처", "가까운", "주변", "지도", "주소", "위치", "길찾기", "경로", "가는 법", "어디",
                "near me", "nearby", "direction", "how to get", "address", "map"
        ));

        /**
         * Ambiguous keywords that are frequently false-positive in TECH queries.
         *
         * <p>Example: "지도" in "지도학습" (supervised learning) is NOT a map intent.</p>
         */
        private List<String> weakKeywords = new ArrayList<>(List.of("지도", "map", "어디", "where"));

        /**
         * "Negative" keywords that suppress location detection to reduce false positives.
         *
         * <p>
         * Typical: ML/tech terms containing "지도".
         * Operators can add/remove freely.
         * </p>
         */
        private List<String> negativeKeywords = new ArrayList<>(List.of(
                "지도학습", "비지도학습", "지도 학습", "비지도 학습",
                "반지도학습", "반지도 학습",
                "자기지도학습", "자기 지도학습", "자기 지도 학습",
                "supervised learning", "unsupervised learning",
                "semi-supervised learning", "self-supervised learning", "self supervised learning"
        ));

        /**
         * Strong local intent keywords. Even if {@link #negativeKeywords} matches, these should
         * still be treated as location intent.
         */
        private List<String> localIntentKeywords = new ArrayList<>(List.of(
                "맛집", "카페", "주유소", "atm", "ATM", "은행", "편의점", "식당",
                "restaurant", "cafe", "gas station"
        ));

        /**
         * Enable/disable strong local intent forcing.
         *
         * <p>Default true (more conservative: blocks overlay when local intent is detected).</p>
         */
        private boolean localIntentEnabled = true;

        /**
         * If true (default), {@link #negativeKeywords} only disables matches coming from {@link #weakKeywords}.
         * If false, negative keywords disable ALL location detection (more aggressive overlay).
         */
        private boolean negativeOnlyAffectsWeakKeywords = true;


        /**
         * Optional: when the query matched ONLY weak location keywords (ex: "지도"/"map"/"어디"),
         * promote it to a real location query if it contains address-like tokens.
         *
         * <p>
         * This is a false-negative reducer. Keep it OFF by default to avoid re-introducing the
         * weak-keyword false-positive problem.
         * </p>
         */
        private boolean weakOnlyPromoteEnabled = false;

        /**
         * Suffix tokens that indicate Korean place/address names.
         * Used only when {@link #weakOnlyPromoteEnabled} is true.
         */
        private List<String> weakOnlyPromoteSuffixes = new ArrayList<>(List.of("역", "구", "동", "로", "길"));

        /**
         * Minimum number of Hangul syllables before the suffix.
         *
         * <p>
         * Default 2: avoids common non-location nouns ending with the same suffix (ex: "번역").
         * </p>
         */
        private int weakOnlyPromoteMinPrefixChars = 2;

        /**
         * Optional: per-suffix minimum Hangul prefix length overrides for weak-only promotion.
         *
         * <p>
         * If a suffix is present in this map, its value overrides {@link #weakOnlyPromoteMinPrefixChars}.
         * This helps balance false positives/negatives by making certain suffixes more/less strict.
         * </p>
         *
         * <p>Example (YAML):</p>
         * <pre>
         * nova.orch.hatch.naver-planhint-boost-only:
         *   location:
         *     weak-only-promote-min-prefix-chars-by-suffix:
         *       역: 1
         *       구: 2
         * </pre>
         */
        private Map<String, Integer> weakOnlyPromoteMinPrefixCharsBySuffix = new LinkedHashMap<>();

        /**
         * Deny keywords for weak-only promotion to reduce translation/meaning false positives.
         * If any matches, the upgrade is suppressed.
         */
        private List<String> weakOnlyPromoteDenyKeywords = new ArrayList<>(List.of(
                "번역", "뜻", "의미", "meaning", "translate", "translation",
                "영어로", "일본어로", "중국어로"
        ));

        /**
         * Allow-list tokens for short district patterns that would not meet {@link #weakOnlyPromoteMinPrefixChars}.
         *
         * <p>Examples: "중구", "동구", "서구", "남구", "북구".</p>
         */
        private List<String> weakOnlyPromoteAllowTokens = new ArrayList<>(List.of(
                "중구", "동구", "서구", "남구", "북구"
        ));


        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = (keywords == null) ? new ArrayList<>() : keywords;
        }

        public List<String> getWeakKeywords() {
            return weakKeywords;
        }

        public void setWeakKeywords(List<String> weakKeywords) {
            this.weakKeywords = (weakKeywords == null) ? new ArrayList<>() : weakKeywords;
        }

        public List<String> getNegativeKeywords() {
            return negativeKeywords;
        }

        public void setNegativeKeywords(List<String> negativeKeywords) {
            this.negativeKeywords = (negativeKeywords == null) ? new ArrayList<>() : negativeKeywords;
        }

        public List<String> getLocalIntentKeywords() {
            return localIntentKeywords;
        }

        public void setLocalIntentKeywords(List<String> localIntentKeywords) {
            this.localIntentKeywords = (localIntentKeywords == null) ? new ArrayList<>() : localIntentKeywords;
        }

        public boolean isLocalIntentEnabled() {
            return localIntentEnabled;
        }

        public void setLocalIntentEnabled(boolean localIntentEnabled) {
            this.localIntentEnabled = localIntentEnabled;
        }

        public boolean isNegativeOnlyAffectsWeakKeywords() {
            return negativeOnlyAffectsWeakKeywords;
        }

        public void setNegativeOnlyAffectsWeakKeywords(boolean negativeOnlyAffectsWeakKeywords) {
            this.negativeOnlyAffectsWeakKeywords = negativeOnlyAffectsWeakKeywords;
        }

        public boolean isWeakOnlyPromoteEnabled() {
            return weakOnlyPromoteEnabled;
        }

        public void setWeakOnlyPromoteEnabled(boolean weakOnlyPromoteEnabled) {
            this.weakOnlyPromoteEnabled = weakOnlyPromoteEnabled;
        }

        public List<String> getWeakOnlyPromoteSuffixes() {
            return weakOnlyPromoteSuffixes;
        }

        public void setWeakOnlyPromoteSuffixes(List<String> weakOnlyPromoteSuffixes) {
            this.weakOnlyPromoteSuffixes = (weakOnlyPromoteSuffixes == null) ? new ArrayList<>() : weakOnlyPromoteSuffixes;
        }

        public int getWeakOnlyPromoteMinPrefixChars() {
            return weakOnlyPromoteMinPrefixChars;
        }

        public void setWeakOnlyPromoteMinPrefixChars(int weakOnlyPromoteMinPrefixChars) {
            this.weakOnlyPromoteMinPrefixChars = Math.max(0, weakOnlyPromoteMinPrefixChars);
        }

        public Map<String, Integer> getWeakOnlyPromoteMinPrefixCharsBySuffix() {
            return weakOnlyPromoteMinPrefixCharsBySuffix;
        }

        public void setWeakOnlyPromoteMinPrefixCharsBySuffix(Map<String, Integer> weakOnlyPromoteMinPrefixCharsBySuffix) {
            if (weakOnlyPromoteMinPrefixCharsBySuffix == null || weakOnlyPromoteMinPrefixCharsBySuffix.isEmpty()) {
                this.weakOnlyPromoteMinPrefixCharsBySuffix = new LinkedHashMap<>();
                return;
            }
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : weakOnlyPromoteMinPrefixCharsBySuffix.entrySet()) {
                if (e == null) {
                    continue;
                }
                String k = (e.getKey() == null) ? null : e.getKey().trim();
                if (k == null || k.isBlank()) {
                    continue;
                }
                Integer v = e.getValue();
                m.put(k, v == null ? 0 : Math.max(0, v));
            }
            this.weakOnlyPromoteMinPrefixCharsBySuffix = m;
        }

        public List<String> getWeakOnlyPromoteDenyKeywords() {
            return weakOnlyPromoteDenyKeywords;
        }

        public void setWeakOnlyPromoteDenyKeywords(List<String> weakOnlyPromoteDenyKeywords) {
            this.weakOnlyPromoteDenyKeywords = (weakOnlyPromoteDenyKeywords == null) ? new ArrayList<>() : weakOnlyPromoteDenyKeywords;
        }

        public List<String> getWeakOnlyPromoteAllowTokens() {
            return weakOnlyPromoteAllowTokens;
        }

        public void setWeakOnlyPromoteAllowTokens(List<String> weakOnlyPromoteAllowTokens) {
            this.weakOnlyPromoteAllowTokens = (weakOnlyPromoteAllowTokens == null) ? new ArrayList<>() : weakOnlyPromoteAllowTokens;
        }
    }
}
