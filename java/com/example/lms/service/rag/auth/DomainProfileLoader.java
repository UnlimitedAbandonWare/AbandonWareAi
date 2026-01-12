package com.example.lms.service.rag.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain profile loader that maintains named collections of domain suffixes.
 *
 * <p>Profiles are used by the web search layer to restrict results to
 * particular categories of websites. Each profile contains a list of
 * domain suffixes (e.g. {@code go.kr}, {@code ac.kr}) that are considered
 * acceptable when the {@code officialSourcesOnly} flag is enabled.</p>
 *
 * <p>Profiles are loaded at startup from a combination of built-in lists,
 * the {@link DomainWhitelist} allowlist and optional external files. A
 * default profile name may be configured via the property
 * {@code domain.allowlist.default-profile}. Additional profiles may be
 * placed in the directory specified by {@code domain.allowlist.external-dir};
 * each {@code *.txt} file will be parsed line by line with comments (#) ignored
 * and added as a new profile keyed by the filename (minus the extension).</p>
 *
 * <p><b>Safety note:</b> Profile matching is performed as "exact host" OR
 * "subdomain of suffix". We intentionally do <i>not</i> use raw {@code endsWith()}
 * because it can allow unrelated domains such as {@code notopenai.com} to match
 * {@code openai.com}.</p>
 */
@Component
public class DomainProfileLoader {
    private static final Logger log = LoggerFactory.getLogger(DomainProfileLoader.class);

    private final DomainWhitelist domainWhitelist;

    @Value("${domain.allowlist.default-profile:official}")
    private String defaultProfile;

    @Value("${domain.allowlist.external-dir:}")
    private String externalDir;

    @Value("${domain.allowlist.admin-token:}")
    private String adminToken;

    // Optional per-profile overrides (comma-separated suffixes).
    // These allow "surgical reconnection" when a profile entry was accidentally removed/added.
    @Value("${domain.allowlist.profile-extra.official:}")
    private String officialExtraCsv;

    @Value("${domain.allowlist.profile-deny.official:}")
    private String officialDenyCsv;

    @Value("${domain.allowlist.profile-extra.docs:}")
    private String docsExtraCsv;

    @Value("${domain.allowlist.profile-deny.docs:}")
    private String docsDenyCsv;

    @Value("${domain.allowlist.profile-extra.dev-community:}")
    private String devCommunityExtraCsv;

    @Value("${domain.allowlist.profile-deny.dev-community:}")
    private String devCommunityDenyCsv;

    /** profileName -> list of allowed suffixes (normalized lower-case). */
    private final Map<String, List<String>> profiles = new LinkedHashMap<>();

    public DomainProfileLoader(DomainWhitelist domainWhitelist) {
        this.domainWhitelist = domainWhitelist;
    }

    /**
     * Initialise the profiles map after bean construction.
     *
     * <p>Built-in profiles:
     * <ul>
     *   <li><b>official</b>: vendor/government/education domains</li>
     *   <li><b>docs</b>: documentation-first domains (includes official)</li>
     *   <li><b>dev_community</b>: Q&A / issue trackers / dev forums (excludes blogs by default)</li>
     *   <li><b>community_blog</b>: dev blog platforms (opt-in)</li>
     *   <li><b>jul14</b>: legacy broad profile preserved for backward compatibility</li>
     * </ul>
     * External profiles (directory *.txt) are loaded last and may override built-ins.</p>
     */
    @PostConstruct
    public synchronized void load() {
        profiles.clear();

        // ─────────────────────────────────────────────────────────────────────
        // 1) OFFICIAL profile
        // ─────────────────────────────────────────────────────────────────────
        LinkedHashSet<String> officialSet = new LinkedHashSet<>();

        // From DomainWhitelist (user config)
        if (domainWhitelist != null && domainWhitelist.getDomainAllowlist() != null) {
            for (String d : domainWhitelist.getDomainAllowlist()) {
                String n = normalizeSuffix(d);
                if (n != null) officialSet.add(n);
            }
        }

        // Minimal fallback: official vendor docs & public-sector suffixes.
        // NOTE: keep these conservative: do NOT include generic community hosts here.
        officialSet.addAll(List.of(
                "go.kr",
                "ac.kr",
                "gov",
                "edu",
                // Google
                "google.dev",
                "developers.google.com",
                "cloud.google.com",
                "support.google.com",
                "googleapis.com",
                // OpenAI
                "openai.com",
                // Anthropic
                "anthropic.com",
                "docs.anthropic.com",
                // Microsoft
                "microsoft.com",
                "learn.microsoft.com",
                // Apple
                "apple.com",
                // Oracle / Java
                "oracle.com",
                "docs.oracle.com",
                // Spring
                "spring.io"
        ));

        // Prevent accidental promotion of community hosts into "official" via misconfigured allowlists.
        // (These belong in dev_community or community_blog profiles.)
        officialSet.removeAll(Set.of(
                "github.com",
                "gitlab.com",
                "bitbucket.org",
                "stackoverflow.com",
                "stackexchange.com",
                "reddit.com",
                "medium.com",
                "hashnode.com",
                "dev.to",
                "velog.io",
                "tistory.com",
                "blog.naver.com",
                "cafe.naver.com",
                "blog.daum.net",
                "cafe.daum.net"
        ));

        applyOverrides(officialSet, officialExtraCsv, officialDenyCsv);

        profiles.put("official", new ArrayList<>(officialSet));

        // ─────────────────────────────────────────────────────────────────────
        // 2) DOCS profile (official + doc-centric domains)
        // ─────────────────────────────────────────────────────────────────────
        LinkedHashSet<String> docsSet = new LinkedHashSet<>(officialSet);
        docsSet.addAll(List.of(
                "developer.mozilla.org",
                "javadoc.io",
                "readthedocs.io",
                "pkg.go.dev",
                "docs.rs",
                "kotlinlang.org",
                "developer.android.com",
                "developer.apple.com",
                "docs.github.com"
        ));
        applyOverrides(docsSet, docsExtraCsv, docsDenyCsv);
        profiles.put("docs", new ArrayList<>(docsSet));

        // ─────────────────────────────────────────────────────────────────────
        // 3) DEV COMMUNITY profile (Q&A, issue trackers, forums)
        // ─────────────────────────────────────────────────────────────────────
        LinkedHashSet<String> devCommunitySet = new LinkedHashSet<>();
        devCommunitySet.addAll(List.of(
                "stackoverflow.com",
                "stackexchange.com",
                "superuser.com",
                "serverfault.com",
                "github.com",
                // NOTE: github.io is a public hosting suffix (GitHub Pages); do NOT classify all *.github.io as dev community.
                "gitlab.com",
                "bitbucket.org",
                "reddit.com",
                // vendor communities
                "community.openai.com"
        ));
        applyOverrides(devCommunitySet, devCommunityExtraCsv, devCommunityDenyCsv);
        // Avoid accidental list overlap: do not allow official suffixes to live in dev_community profile.
        devCommunitySet.removeAll(officialSet);
        profiles.put("dev_community", new ArrayList<>(devCommunitySet));

        // ─────────────────────────────────────────────────────────────────────
        // 4) COMMUNITY BLOG profile (opt-in)
        // ─────────────────────────────────────────────────────────────────────
        LinkedHashSet<String> blogSet = new LinkedHashSet<>();
        blogSet.addAll(List.of(
                "medium.com",
                "hashnode.com",
                "dev.to",
                "velog.io",
                "tistory.com",
                "blog.naver.com",
                "brunch.co.kr",
                "blog.daum.net",
                "wordpress.com",
                "blogspot.com",
                "qiita.com",
                "zenn.dev",
                // KR engineering blogs (high-signal but still blogs)
                "d2.naver.com",
                "tech.kakao.com",
                "engineering.linecorp.com",
                "techblog.woowahan.com",
                "woowabros.github.io",
                "spoqa.github.io"
        ));
        profiles.put("community_blog", new ArrayList<>(blogSet));

        // ─────────────────────────────────────────────────────────────────────
        // 5) KR company profiles (used by entity/company lookups and UAW idle pipeline)
        // ─────────────────────────────────────────────────────────────────────
        LinkedHashSet<String> krCompanyDisclosure = new LinkedHashSet<>(officialSet);
        krCompanyDisclosure.addAll(List.of(
                "dart.fss.or.kr",
                "opendart.fss.or.kr",
                "fss.or.kr",
                "kind.krx.co.kr",
                "krx.co.kr"
        ));
        profiles.put("kr_company_disclosure", new ArrayList<>(krCompanyDisclosure));

        LinkedHashSet<String> krCompanyStartup = new LinkedHashSet<>(officialSet);
        krCompanyStartup.addAll(List.of(
                "rocketpunch.com",
                "thevc.kr"
        ));
        profiles.put("kr_company_startup", new ArrayList<>(krCompanyStartup));

        LinkedHashSet<String> krCompanyHiring = new LinkedHashSet<>(officialSet);
        krCompanyHiring.addAll(List.of(
                "wanted.co.kr",
                "saramin.co.kr",
                "incruit.com",
                "jobplanet.co.kr",
                "jumpit.saramin.co.kr"
        ));
        profiles.put("kr_company_hiring", new ArrayList<>(krCompanyHiring));

        LinkedHashSet<String> krCompanyPress = new LinkedHashSet<>(officialSet);
        krCompanyPress.addAll(List.of(
                "news.naver.com",
                "yonhapnews.co.kr",
                "hankyung.com",
                "mk.co.kr"
        ));
        profiles.put("kr_company_press", new ArrayList<>(krCompanyPress));

        LinkedHashSet<String> krCompany = new LinkedHashSet<>(officialSet);
        krCompany.addAll(krCompanyDisclosure);
        krCompany.addAll(krCompanyStartup);
        krCompany.addAll(krCompanyHiring);
        krCompany.addAll(krCompanyPress);
        profiles.put("kr_company", new ArrayList<>(krCompany));


        // ─────────────────────────────────────────────────────────────────────
        // 6) Legacy broad profile (jul14)
        // ─────────────────────────────────────────────────────────────────────
        // Keep backward compatibility for older flows that may reference "jul14".
        LinkedHashSet<String> jul14 = new LinkedHashSet<>();
        jul14.addAll(officialSet);
        jul14.addAll(docsSet);
        jul14.addAll(devCommunitySet);
        jul14.addAll(blogSet);
        // KR company profile directories (useful for entity/company lookups)
        jul14.addAll(List.of(
                "rocketpunch.com",
                "jobplanet.co.kr",
                "incruit.com",
                "saramin.co.kr",
                "wanted.co.kr"
        ));
        // Historically included some wiki-like sources; keep them only in legacy profile.
        jul14.addAll(List.of(
                "wikipedia.org",
                "wikidata.org",
                "namu.wiki"
        ));
        profiles.put("jul14", new ArrayList<>(jul14));

        // ─────────────────────────────────────────────────────────────────────
        // 7) Load external profiles from directory
        // ─────────────────────────────────────────────────────────────────────
        if (externalDir != null && !externalDir.isBlank()) {
            try {
                Path dir = Paths.get(externalDir);
                if (Files.isDirectory(dir)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
                        for (Path p : stream) {
                            String name = p.getFileName().toString();
                            if (name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                                String profileName = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                                List<String> list = new ArrayList<>();
                                for (String line : Files.readAllLines(p)) {
                                    String trimmed = (line == null) ? "" : line.trim();
                                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                                    String n = normalizeSuffix(trimmed);
                                    if (n != null) list.add(n);
                                }
                                profiles.put(profileName, list);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Gracefully ignore directory loading failures
                log.debug("[DomainProfileLoader] external profile load skipped: {}", e.toString());
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[DomainProfileLoader] loaded profiles: {}", profiles.keySet());
        }
    }

    /** Reload profiles from the external directory. */
    public synchronized void reload() {
        load();
    }

    /**
     * Determine whether the given URL is permitted by the specified profile.
     * If the profile name is null or blank, the default profile is used.
     * If the profile cannot be found (or is empty), the "official" profile is used as a fallback.
     *
     * @param url the URL to evaluate
     * @param profile the desired profile name
     * @return true if the URL is allowed by the profile, false otherwise
     */
    public boolean isAllowedByProfile(String url, String profile) {
        if (url == null || url.isBlank()) return false;

        String effectiveProfile = (profile == null || profile.isBlank()) ? defaultProfile : profile;
        String key = effectiveProfile.toLowerCase(Locale.ROOT).trim();

        List<String> list = profiles.get(key);
        if (list == null || list.isEmpty()) {
            list = profiles.get("official");
        }
        if (list == null || list.isEmpty()) {
            return false;
        }

        String host = extractHost(url);
        if (host == null || host.isBlank()) return false;

        String lowerHost = normalizeHost(host);
        if (lowerHost == null) return false;

        for (String suf : list) {
            String s = normalizeSuffix(suf);
            if (s == null) continue;
            if (hostMatchesSuffix(lowerHost, s)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if the input profile exists. */
    public boolean hasProfile(String name) {
        if (name == null || name.isBlank()) return false;
        return profiles.containsKey(name.toLowerCase(Locale.ROOT).trim());
    }

    /** Returns a copy of entries for the given profile (empty when missing). */
    public List<String> getProfileEntries(String name) {
        if (name == null || name.isBlank()) return List.of();
        List<String> list = profiles.get(name.toLowerCase(Locale.ROOT).trim());
        if (list == null) return List.of();
        return new ArrayList<>(list);
    }

    /**
     * Retrieve a list of available profiles with their respective sizes.
     * This method is used by the API to expose profile metadata to the frontend.
     */
    public List<Map<String, Object>> listProfiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : profiles.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("size", e.getValue() != null ? e.getValue().size() : 0);
            out.add(m);
        }
        return out;
    }

    /** Return the configured default profile. */
    public String getDefaultProfile() {
        return (defaultProfile == null || defaultProfile.isBlank()) ? "official" : defaultProfile;
    }

    /** Expose the configured admin token (if any). */
    public String getAdminToken() {
        return adminToken == null ? "" : adminToken;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void applyOverrides(Set<String> target, String extraCsv, String denyCsv) {
        if (target == null) return;
        try {
            for (String s : parseCsvSuffixSet(extraCsv)) {
                String n = normalizeSuffix(s);
                if (n != null) target.add(n);
            }
        } catch (Exception ignore) {
        }
        try {
            for (String s : parseCsvSuffixSet(denyCsv)) {
                String n = normalizeSuffix(s);
                if (n != null) target.remove(n);
            }
        } catch (Exception ignore) {
        }
    }

    private static Set<String> parseCsvSuffixSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        // split on commas or whitespace
        for (String tok : csv.split("[,\\s]+")) {
            if (tok == null) continue;
            String t = tok.trim();
            if (t.isEmpty()) continue;
            // allow comments inline (foo.com # comment)
            int hash = t.indexOf('#');
            if (hash >= 0) t = t.substring(0, hash).trim();
            if (t.isEmpty()) continue;
            out.add(t);
        }
        return out;
    }

    private static String extractHost(String url) {
        String host = null;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception ignore) {
        }
        if (host == null || host.isBlank()) {
            try {
                host = java.net.URI.create("https://" + url).getHost();
            } catch (Exception ignore) {
            }
        }
        return host;
    }

    private static boolean hostMatchesSuffix(String lowerHost, String suffix) {
        if (lowerHost == null || lowerHost.isBlank() || suffix == null || suffix.isBlank()) return false;
        // exact host OR subdomain match.  Prevent "notopenai.com" matching "openai.com".
        return lowerHost.equals(suffix) || lowerHost.endsWith("." + suffix);
    }

    private static String normalizeHost(String h) {
        if (h == null) return null;
        String s = h.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.startsWith("www.")) s = s.substring(4);
        return s;
    }

    private static String normalizeSuffix(String s) {
        if (s == null) return null;
        String x = s.trim().toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return null;
        while (x.startsWith(".")) x = x.substring(1);
        // defensive: some profiles may accidentally include schemes
        if (x.startsWith("http://")) x = x.substring(7);
        if (x.startsWith("https://")) x = x.substring(8);
        // remove trailing slash
        while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
        if (x.isEmpty()) return null;
        return x;
    }
}
