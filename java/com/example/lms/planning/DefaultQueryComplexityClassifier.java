
package com.example.lms.planning;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;




@Component
@ConditionalOnProperty(name = "query.complexity.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultQueryComplexityClassifier implements QueryComplexityClassifier {

    private static final Set<String> WH_WORDS = new HashSet<>(Arrays.asList(
            "who","what","when","where","why","how","which","whom","whose",
            "누가","무엇","언제","어디","왜","어떻게","어느","몇"));
    private static final Set<String> RECENTNESS_HINTS = new HashSet<>(Arrays.asList(
            "today","latest","news","update","breaking","release","2025","2024",
            "오늘","최근","최신","방금","지금","업데이트","발표"));
    private static final Pattern ENTITY_LIKE = Pattern.compile("[A-Z][a-z]+(\\s+[A-Z][a-z]+)+|[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+");

    @Override
    public ComplexityScore score(String q) {
        if (q == null) q = "";
        String query = q.trim();
        int len = query.length();
        int tokens = query.isEmpty()? 0 : query.split("\\s+").length;
        long wh = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")).filter(WH_WORDS::contains).count();
        long recent = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")).filter(RECENTNESS_HINTS::contains).count();
        boolean hasEntity = ENTITY_LIKE.matcher(query).find();
        double nounish = hasEntity ? 0.8 : 0.3;

        double simpleScore = Math.max(0, 1.0 - (tokens/12.0)) * (wh==0?1.0:0.8);
        double complexScore = Math.min(1.0, (tokens/8.0) + (wh>0?0.2:0.0) + (hasEntity?0.1:0.0));
        double webNeed = Math.min(1.0, recent*0.6 + (query.contains("?")?0.1:0.0) + (nounish<0.5?0.1:0.0));

        ComplexityScore.Label label;
        double certainty;
        if (webNeed > 0.6) { label = ComplexityScore.Label.WEB_RECENT; certainty = webNeed; }
        else if (complexScore > 0.55) { label = ComplexityScore.Label.COMPLEX; certainty = complexScore; }
        else { label = ComplexityScore.Label.SIMPLE; certainty = simpleScore; }

        Map<String, Double> feats = new LinkedHashMap<>();
        feats.put("len", (double) len);
        feats.put("tokens", (double) tokens);
        feats.put("wh", (double) wh);
        feats.put("recentHints", (double) recent);
        feats.put("nounish", nounish);
        feats.put("simpleScore", simpleScore);
        feats.put("complexScore", complexScore);
        feats.put("webNeed", webNeed);
        return new ComplexityScore(label, Math.min(1.0, Math.max(0.0, certainty)), Collections.unmodifiableMap(feats));
    }
}