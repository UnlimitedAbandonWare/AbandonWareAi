package guard;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.lms.service.rag.auth.DomainWhitelist;

/**
 * Citation gate: require at least N whitelisted sources and total snippet length >= T.
 */
public class FinalQualityGate {

    private final DomainWhitelist whitelist;
    private int minOfficialSources = 2;
    private int minSnippetChars = 400;

    public FinalQualityGate(DomainWhitelist whitelist) {
        this.whitelist = whitelist;
    }

    public void configure(int minSources, int minChars) {
        this.minOfficialSources = minSources;
        this.minSnippetChars = minChars;
    }

    public boolean approve(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return false;
        int totalChars = evidences.stream().mapToInt(e -> e.snippet == null ? 0 : e.snippet.length()).sum();
        long officialCount = evidences.stream().map(e -> e.source).filter(s -> whitelist.isOfficial(s)).distinct().count();
        return officialCount >= minOfficialSources && totalChars >= minSnippetChars;
    }

    public static class Evidence {
        public final String source;
        public final String snippet;
        public Evidence(String source, String snippet) {
            this.source = source;
            this.snippet = snippet;
        }
    }
}