package com.example.lms.planner;
import java.util.Locale;
public final class QueryComplexityClassifier {
    public enum Complexity { SIMPLE, MODERATE, COMPLEX, WEB_REQUIRED }
    public Complexity classify(String q) {
        if (q == null) q = "";
        int len = q.length();
        boolean hasQ = q.contains("?");
        boolean temporal = q.matches(".*(최근|오늘|올해|지난|202\\d).*");
        int score = (len>50?1:0) + (temporal?1:0) + (hasQ?1:0);
        if (temporal) return Complexity.WEB_REQUIRED;
        return score>=2? Complexity.COMPLEX : (score==1?Complexity.MODERATE:Complexity.SIMPLE);
    }
}