package com.example.lms.service.orchestrator;
import java.util.regex.Pattern;
public final class QueryComplexityClassifier {
  public enum Complexity { LOW, MID, HIGH }
  private static final Pattern PUNCT = Pattern.compile("[\\?\\!]+");
  private static final Pattern WH = Pattern.compile("\\b(who|what|when|where|why|how)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern TENSE = Pattern.compile("\\b(latest|recent|today|202\\d|\\d{4})\\b", Pattern.CASE_INSENSITIVE);
  public Complexity classify(String q) {
    if (q == null) return Complexity.LOW;
    int len = q.codePointCount(0, q.length()); int s=0;
    if (len>=80) s+=2; else if (len>=40) s+=1;
    if (PUNCT.matcher(q).find()) s+=1;
    if (WH.matcher(q).find()) s+=1;
    if (TENSE.matcher(q).find()) s+=1;
    return s>=3?Complexity.HIGH:(s>=2?Complexity.MID:Complexity.LOW);
  }
}
