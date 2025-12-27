package guard;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(FinalQualityGate.class);


    public FinalQualityGate(DomainWhitelist whitelist) {
        this.whitelist = whitelist;
    }

    public void configure(int minSources, int minChars) {
        this.minOfficialSources = minSources;
        this.minSnippetChars = minChars;
    }

    public boolean approve(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return false;
        int totalChars = evidences.stream()
                .mapToInt(e -> e.snippet == null ? 0 : e.snippet.length())
                .sum();
        long officialCount = evidences.stream()
                .map(e -> e.source)
                .filter(s -> whitelist.isOfficial(s))
                .distinct()
                .count();

        boolean hasEnoughOfficial =
                officialCount >= minOfficialSources && totalChars >= minSnippetChars;

        boolean hasGameCommunitySource = false;
        for (Evidence e : evidences) {
            String url = e.source;
            if (url == null || url.isBlank()) {
                continue;
            }
            String host = null;
            try {
                host = URI.create(url).getHost();
            } catch (Exception ignore) {
                // ignore malformed URLs and continue
            }
            if (host == null || host.isBlank()) {
                continue;
            }
            if (host.endsWith("namu.wiki") || host.endsWith("tistory.com")) {
                hasGameCommunitySource = true;
                break;
            }
        }

        int relaxedMinChars = 200; // 게임/커뮤니티용 최소 길이

        // [마비카 Fix] 게임/커뮤니티 도메인 완화:
        // 나무위키/티스토리 출처가 있고, 총 스니펫 길이가 어느 정도 확보되면
        // "부분적으로는 답변 가능"하다고 보고 PASS 처리한다.
        if (!hasEnoughOfficial && hasGameCommunitySource && totalChars >= relaxedMinChars) {
            if (log.isDebugEnabled()) {
                log.debug("[FinalQualityGate] relaxed pass for game/community sources: officialCount={}, totalChars={}",
                        Long.valueOf(officialCount), Integer.valueOf(totalChars));
            }
            return true;
        }

        // 기존 공식 출처 기준
        return hasEnoughOfficial;
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