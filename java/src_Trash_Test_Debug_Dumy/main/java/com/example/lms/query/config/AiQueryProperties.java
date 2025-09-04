package com.example.lms.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binding class for the alias.yml configuration.  This class maps the YAML
 * structure into a strongly typed object graph.  See src/main/resources/alias.yml
 * for the default values and documentation of each field.  The prefix
 * {@code ai.query} is used when binding properties from the environment.
 */
@ConfigurationProperties(prefix = "ai.query")
public class AiQueryProperties {

    private String version;
    private String updated;
    private String language = "ko";

    private Norm norm = new Norm();
    private Tokenize tokenize = new Tokenize();
    private Rewrite rewrite = new Rewrite();
    private List<AliasGroup> aliases = new ArrayList<>();
    private Domains domains = new Domains();
    private Recency recency = new Recency();
    private Weights weights = new Weights();
    private Fallbacks fallbacks = new Fallbacks();

    // catch‑all for unbound properties (future extension)
    private Map<String, Object> extra;

    public static class Norm {
        private boolean lowerCase = true;
        private boolean trimPunct = true;
        private boolean normalizeSpace = true;
        private boolean hanjaToHangul = false;
        private boolean foldWidth = false;
        private boolean removeUtmParams = true;

        public boolean isLowerCase() { return lowerCase; }
        public void setLowerCase(boolean lowerCase) { this.lowerCase = lowerCase; }
        public boolean isTrimPunct() { return trimPunct; }
        public void setTrimPunct(boolean trimPunct) { this.trimPunct = trimPunct; }
        public boolean isNormalizeSpace() { return normalizeSpace; }
        public void setNormalizeSpace(boolean normalizeSpace) { this.normalizeSpace = normalizeSpace; }
        public boolean isHanjaToHangul() { return hanjaToHangul; }
        public void setHanjaToHangul(boolean hanjaToHangul) { this.hanjaToHangul = hanjaToHangul; }
        public boolean isFoldWidth() { return foldWidth; }
        public void setFoldWidth(boolean foldWidth) { this.foldWidth = foldWidth; }
        public boolean isRemoveUtmParams() { return removeUtmParams; }
        public void setRemoveUtmParams(boolean removeUtmParams) { this.removeUtmParams = removeUtmParams; }
    }

    public static class Tokenize {
        private int ngramMin = 1;
        private int ngramMax = 2;
        private List<String> stopwords = List.of();
        private List<String> blockTerms = List.of();
        private List<String> forceTerms = List.of();

        public int getNgramMin() { return ngramMin; }
        public void setNgramMin(int ngramMin) { this.ngramMin = ngramMin; }
        public int getNgramMax() { return ngramMax; }
        public void setNgramMax(int ngramMax) { this.ngramMax = ngramMax; }
        public List<String> getStopwords() { return stopwords; }
        public void setStopwords(List<String> stopwords) { this.stopwords = stopwords; }
        public List<String> getBlockTerms() { return blockTerms; }
        public void setBlockTerms(List<String> blockTerms) { this.blockTerms = blockTerms; }
        public List<String> getForceTerms() { return forceTerms; }
        public void setForceTerms(List<String> forceTerms) { this.forceTerms = forceTerms; }
    }

    public static class Rewrite {
        private List<Map<String, String>> replace = List.of();
        private List<AppendRule> appendTerms = List.of();
        public List<Map<String, String>> getReplace() { return replace; }
        public void setReplace(List<Map<String, String>> replace) { this.replace = replace; }
        public List<AppendRule> getAppendTerms() { return appendTerms; }
        public void setAppendTerms(List<AppendRule> appendTerms) { this.appendTerms = appendTerms; }

        public static class AppendRule {
            private List<String> ifContains = List.of();
            private List<String> add = List.of();
            public List<String> getIfContains() { return ifContains; }
            public void setIfContains(List<String> ifContains) { this.ifContains = ifContains; }
            public List<String> getAdd() { return add; }
            public void setAdd(List<String> add) { this.add = add; }
        }
    }

    public static class AliasGroup {
        private String canonical;
        private List<String> synonyms = List.of();
        private List<String> domainAllow = List.of();
        private List<String> domainDeny = List.of();
        private String notes;
        public String getCanonical() { return canonical; }
        public void setCanonical(String canonical) { this.canonical = canonical; }
        public List<String> getSynonyms() { return synonyms; }
        public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
        public List<String> getDomainAllow() { return domainAllow; }
        public void setDomainAllow(List<String> domainAllow) { this.domainAllow = domainAllow; }
        public List<String> getDomainDeny() { return domainDeny; }
        public void setDomainDeny(List<String> domainDeny) { this.domainDeny = domainDeny; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    public static class Domains {
        private List<String> allow = List.of();
        private List<String> deny = List.of();
        public List<String> getAllow() { return allow; }
        public void setAllow(List<String> allow) { this.allow = allow; }
        public List<String> getDeny() { return deny; }
        public void setDeny(List<String> deny) { this.deny = deny; }
    }

    public static class Recency {
        private int boostDays = 60;
        private double maxBonus = 0.30;
        public int getBoostDays() { return boostDays; }
        public void setBoostDays(int boostDays) { this.boostDays = boostDays; }
        public double getMaxBonus() { return maxBonus; }
        public void setMaxBonus(double maxBonus) { this.maxBonus = maxBonus; }
    }

    public static class Weights {
        private double selfAsk = 1.2;
        private double web = 1.0;
        private double vector = 1.5;
        public double getSelfAsk() { return selfAsk; }
        public void setSelfAsk(double selfAsk) { this.selfAsk = selfAsk; }
        public double getWeb() { return web; }
        public void setWeb(double web) { this.web = web; }
        public double getVector() { return vector; }
        public void setVector(double vector) { this.vector = vector; }
    }

    public static class Fallbacks {
        private String whenNoProvider = "mock";
        private boolean safeMode = true;
        public String getWhenNoProvider() { return whenNoProvider; }
        public void setWhenNoProvider(String whenNoProvider) { this.whenNoProvider = whenNoProvider; }
        public boolean isSafeMode() { return safeMode; }
        public void setSafeMode(boolean safeMode) { this.safeMode = safeMode; }
    }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getUpdated() { return updated; }
    public void setUpdated(String updated) { this.updated = updated; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Norm getNorm() { return norm; }
    public void setNorm(Norm norm) { this.norm = norm; }
    public Tokenize getTokenize() { return tokenize; }
    public void setTokenize(Tokenize tokenize) { this.tokenize = tokenize; }
    public Rewrite getRewrite() { return rewrite; }
    public void setRewrite(Rewrite rewrite) { this.rewrite = rewrite; }
    public List<AliasGroup> getAliases() { return aliases; }
    public void setAliases(List<AliasGroup> aliases) { this.aliases = aliases; }
    public Domains getDomains() { return domains; }
    public void setDomains(Domains domains) { this.domains = domains; }
    public Recency getRecency() { return recency; }
    public void setRecency(Recency recency) { this.recency = recency; }
    public Weights getWeights() { return weights; }
    public void setWeights(Weights weights) { this.weights = weights; }
    public Fallbacks getFallbacks() { return fallbacks; }
    public void setFallbacks(Fallbacks fallbacks) { this.fallbacks = fallbacks; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }
}