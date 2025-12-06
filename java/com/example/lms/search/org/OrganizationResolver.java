package com.example.lms.search.org;

import lombok.extern.slf4j.Slf4j;
import com.example.lms.search.CompanyNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.*;





/**
 * Resolves organisations from a YAML catalogue based on fuzzy alias matching.
 *
 * <p>The resolver loads {@code orgs.yml} at startup and caches the entries.  It
 * exposes a single {@link #resolve(String)} method that returns the best
 * matching organisation given an input query.  Matching considers exact
 * canonical/alias matches, partial containment and Levenshtein distance with a
 * threshold ≤2.  The input is preprocessed via {@link CompanyNormalizer} to
 * remove legal suffixes and typos before matching.</p>
 */
@Component("searchOrganizationResolver")
@RequiredArgsConstructor
@Slf4j
public class OrganizationResolver {

    private final ResourceLoader resourceLoader;
    private final CompanyNormalizer normalizer;

    /** Configuration property pointing to the catalogue file. */
    @Value("${org-catalog.file:classpath:/orgs.yml}")
    private String catalogFile;

    /** Loaded organisation definitions. */
    @Getter
    private List<Org> orgs = List.of();

    /** Load the YAML catalogue on startup. */
    @PostConstruct
    public void init() {
        try {
            Resource resource = resourceLoader.getResource(catalogFile);
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Object root = yaml.load(is);
                if (root instanceof Map<?, ?> map) {
                    Object orgsObj = map.get("orgs");
                    if (orgsObj instanceof Iterable<?> it) {
                        List<Org> list = new ArrayList<>();
                        for (Object item : it) {
                            if (item instanceof Map<?, ?> mm) {
                                Org o = new Org();
                                o.canonical = Objects.toString(mm.get("canonical"), null);
                                o.aliases = asList(mm.get("aliases"));
                                o.regions = asList(mm.get("regions"));
                                o.sites = asList(mm.get("sites"));
                                list.add(o);
                            }
                        }
                        this.orgs = list;
                    }
                }
            }
        } catch (Exception e) {
            // fall back to empty list on any failure
            this.orgs = List.of();
        }
    }

    private List<String> asList(Object obj) {
        if (obj instanceof Iterable<?> it) {
            List<String> list = new ArrayList<>();
            for (Object o : it) {
                if (o != null) list.add(o.toString());
            }
            return list;
        }
        return List.of();
    }

    /**
     * Resolve the most likely organisation for the given query.
     * @param q raw user query
     * @return optional org entry if one is matched
     */
    public Optional<Org> resolve(String q) {
        if (q == null || q.isBlank()) return Optional.empty();
        String normalized = normalizer.normalize(q);
        if (normalized == null) return Optional.empty();
        // strip non-alphanum to compare roughly
        String key = normalized.replaceAll("[^\\p{IsHangul}A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        Org best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Org org : orgs) {
            List<String> names = new ArrayList<>();
            if (org.canonical != null) names.add(org.canonical);
            if (org.aliases != null) names.addAll(org.aliases);
            for (String name : names) {
                String n = name.replaceAll("[^\\p{IsHangul}A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (n.equals(key) || key.equals(n)) {
                    return Optional.of(org);
                }
                if (n.contains(key) || key.contains(n)) {
                    return Optional.of(org);
                }
                int dist = levenshtein(key, n);
                if (dist <= 2 && dist < bestScore) {
                    bestScore = dist;
                    best = org;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    /**
     * Simple POJO representing a single organisation entry.
     */
    public static class Org {
        public String canonical;
        public List<String> aliases = List.of();
        public List<String> regions = List.of();
        public List<String> sites = List.of();
        public String canonical() { return canonical; }
        public List<String> sites() { return sites == null ? List.of() : sites; }
    }
}