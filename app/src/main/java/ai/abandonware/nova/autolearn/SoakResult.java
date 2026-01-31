package ai.abandonware.nova.autolearn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakResult {
    private final String query;
    private final boolean pass;
    private final String answer;
    private final List<String> sources;
    private final String modeUsed;
    private final double score;
    private final int citationCount;
    private final String failReason;

    private SoakResult(String query, boolean pass, String answer, List<String> sources,
                       String modeUsed, double score, int citationCount, String failReason) {
        this.query = query;
        this.pass = pass;
        this.answer = answer;
        this.sources = sources;
        this.modeUsed = modeUsed;
        this.score = score;
        this.citationCount = citationCount;
        this.failReason = failReason;
    }

    public static SoakResult pass(String q, String a, List<String> src, String mode, double score) {
        int cc = (src == null) ? 0 : src.size();
        return new SoakResult(q, true, a, src == null ? Collections.emptyList() : src, mode, score, cc, null);
    }
    public static SoakResult failed(String q, String reason) {
        return new SoakResult(q, false, null, Collections.emptyList(), "none", 0.0, 0, reason);
    }

    public boolean isPass() { return pass; }
    public String getQuery() { return query; }
    public String getAnswer() { return answer; }
    public List<String> getSources() { return sources; }
    public String getModeUsed() { return modeUsed; }
    public double getScore() { return score; }
    public int getCitationCount() { return citationCount; }
    public String getFailReason() { return failReason; }
}