package com.example.lms.service.rag.auth;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;





/**
 * Domain profile loader that maintains named collections of domain suffixes.
 *
 * <p>Profiles are used by the web search layer to restrict results to
 * particular categories of websites.  Each profile contains a list of
 * domain suffixes (e.g. "go.kr", "ac.kr") that are considered
 * acceptable when the {@code officialSourcesOnly} flag is enabled.
 * Profiles are loaded at startup from a combination of built-in lists,
 * the {@link DomainWhitelist} allowlist and optional external files.  A
 * default profile name may be configured via the property
 * {@code domain.allowlist.default-profile}.  Additional profiles may be
 * placed in the directory specified by {@code domain.allowlist.external-dir};
 * each *.txt file will be parsed line by line with comments (#) ignored
 * and added as a new profile keyed by the filename (minus the extension).</p>
 */
@Component
@Slf4j
public class DomainProfileLoader {

    private final DomainWhitelist domainWhitelist;

    @Value("${domain.allowlist.default-profile:official}")
    private String defaultProfile;

    @Value("${domain.allowlist.external-dir:}")
    private String externalDir;

    @Value("${domain.allowlist.admin-token:}")
    private String adminToken;

    private final Map<String, List<String>> profiles = new LinkedHashMap<>();

    public DomainProfileLoader(DomainWhitelist domainWhitelist) {
        this.domainWhitelist = domainWhitelist;
    }

    /**
     * Initialise the profiles map after bean construction.  This method
     * populates a default "official" profile from the {@link DomainWhitelist}
     * allowlist, a built-in "jul14" profile containing a broader set of
     * community and blog domains, and any additional profiles found in the
     * configured external directory.
     */
    @PostConstruct
    public synchronized void load() {
        profiles.clear();
        // Official profile from DomainWhitelist
        List<String> official = new ArrayList<>();
        if (domainWhitelist != null && domainWhitelist.getDomainAllowlist() != null) {
            official.addAll(domainWhitelist.getDomainAllowlist());
        }
        profiles.put("official", official);
        // Built-in jul14 profile: start with official and augment with common blogs/forums
        List<String> jul14 = new ArrayList<>(official);
        jul14.addAll(List.of(
                "blog.naver.com", "tistory.com", "cafe.naver.com", "velog.io", "medium.com",
                "brunch.co.kr", "post.naver.com", "blog.daum.net", "hashnode.com",
                "dev.to", "zenn.dev", "qiita.com", "wordpress.com", "blogspot.com",
                "engineering.linecorp.com", "tech.kakao.com", "d2.naver.com",
                "techblog.woowahan.com", "woowabros.github.io", "spoqa.github.io",
                "namu.wiki", "wikidata.org", "wikipedia.org"
        ));
        profiles.put("jul14", jul14);
        // Load external profiles from directory
        if (externalDir != null && !externalDir.isBlank()) {
            try {
                Path dir = Paths.get(externalDir);
                if (Files.isDirectory(dir)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
                        for (Path p : stream) {
                            String name = p.getFileName().toString();
                            if (name.toLowerCase().endsWith(".txt")) {
                                String profileName = name.substring(0, name.length() - 4);
                                List<String> list = new ArrayList<>();
                                List<String> lines = Files.readAllLines(p);
                                for (String line : lines) {
                                    String trimmed = line.trim();
                                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                                        list.add(trimmed);
                                    }
                                }
                                profiles.put(profileName.toLowerCase(), list);
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
                // Gracefully ignore directory loading failures
            }
        }
    }

    /** Reload profiles from the external directory. */
    public synchronized void reload() {
        load();
    }

    /**
     * Determine whether the given URL is permitted by the specified profile.
     * If the profile name is null or blank, the default profile is used.  If
     * the profile cannot be found, the "official" profile is used as a
     * fallback.  Matching is performed on the host suffix.
     *
     * @param url the URL to evaluate
     * @param profile the desired profile name
     * @return true if the URL is allowed by the profile, false otherwise
     */
    public boolean isAllowedByProfile(String url, String profile) {
        if (url == null || url.isBlank()) return false;
        String effectiveProfile = (profile == null || profile.isBlank()) ? defaultProfile : profile;
        String key = effectiveProfile.toLowerCase();
        List<String> list = profiles.get(key);
        if (list == null) {
            // Fallback to official if unknown
            list = profiles.get("official");
        }
        if (list == null) {
            list = Collections.emptyList();
        }
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return false;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            for (String suf : list) {
                String s = suf == null ? null : suf.trim().toLowerCase(Locale.ROOT);
                if (s == null || s.isEmpty()) continue;
                if (lowerHost.endsWith(s)) {
                    return true;
                }
            }
        } catch (Exception ignore) {
            // Malformed URLs are not considered allowed
        }
        return false;
    }

    /**
     * Retrieve a list of available profiles with their respective sizes.  This
     * method is used by the API to expose profile metadata to the frontend.
     *
     * @return a list of maps containing the profile name and entry count
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

    /**
     * Return the configured default profile.  When no default is configured
     * this method returns "official".
     *
     * @return the name of the default profile
     */
    public String getDefaultProfile() {
        return (defaultProfile == null || defaultProfile.isBlank()) ? "official" : defaultProfile;
    }

    /**
     * Expose the configured admin token (if any).  When present the token
     * can be used to authorise profile reloads via the REST API.  If no
     * token is set this returns an empty string.
     *
     * @return the admin token
     */
    public String getAdminToken() {
        return adminToken == null ? "" : adminToken;
    }
}