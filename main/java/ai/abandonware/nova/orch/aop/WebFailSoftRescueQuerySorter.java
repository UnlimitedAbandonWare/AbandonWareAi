package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Locale;

final class WebFailSoftRescueQuerySorter {

    private WebFailSoftRescueQuerySorter() {
    }

    static void sortOfficialDocsRescueQueries(List<String> candidates,
                                              @Nullable GuardContext ctx,
                                              String tracePrefix) {
        if (candidates == null) {
            return;
        }
        boolean deterministic = ctx != null && ctx.planBool("starvationLadder.deterministic", false);
        candidates.sort((a, b) -> {
            int score = Integer.compare(scoreOfficialDocsRescueQuery(b), scoreOfficialDocsRescueQuery(a));
            if (score != 0 || !deterministic) {
                return score;
            }
            return rescueSortKey(a).compareTo(rescueSortKey(b));
        });
        try {
            String prefix = (tracePrefix == null || tracePrefix.isBlank())
                    ? "web.failsoft.rescueSort"
                    : tracePrefix;
            TraceStore.put(prefix + ".deterministic", deterministic);
            TraceStore.put(prefix + ".candidateCount", candidates.size());
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("webFailSoftRescueQuerySorter.trace", ignore);
        }
    }

    static int scoreOfficialDocsRescueQuery(@Nullable String q) {
        if (q == null) {
            return 0;
        }
        String raw = q.trim();
        if (raw.isEmpty()) {
            return 0;
        }

        int score = 0;
        if (raw.contains("\uACF5\uC2DD") || raw.contains("\uBB38\uC11C")
                || raw.contains("\uB9E4\uB274\uC5BC") || raw.contains("\uB808\uD37C\uB7F0\uC2A4")
                || raw.contains("\uC0AC\uC774\uD2B8") || raw.contains("\uAC00\uC774\uB4DC")
                || raw.contains("\uC778\uD130\uD398\uC774\uC2A4") || raw.contains("\uAC1C\uBC1C\uC790")
                || raw.contains("API")) {
            score += 3;
        }

        String t = raw.toLowerCase(Locale.ROOT);
        if (t.contains("official") || t.contains("docs") || t.contains("documentation") || t.contains("developer")
                || t.contains("developers") || t.contains("api reference") || t.contains("reference")) {
            score += 3;
        }
        if (t.contains("changelog") || t.contains("release notes") || t.contains("latest")
                || t.contains("current")) {
            score += 4;
        }
        if (t.contains("site:") || t.contains("docs.") || t.contains("developer.") || t.contains("developers.")) {
            score += 1;
        }
        if (t.contains("github.com") || t.contains("readthedocs") || t.contains("confluence")
                || t.contains("notion.site")) {
            score += 1;
        }
        return score;
    }

    private static String rescueSortKey(@Nullable String query) {
        if (query == null) {
            return "";
        }
        return query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
