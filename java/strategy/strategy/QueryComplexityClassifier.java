package strategy.strategy;

public final class QueryComplexityClassifier {
  private QueryComplexityClassifier() {}
  public static boolean isComplex(String q) {
    if (q == null) return false;
    int tokens = q.trim().split("\\s+").length;
    int wh = count(q, new String[]{"왜","어떻게","무엇","which","how","why"});
    int ops = count(q, new String[]{" and "," or ",",",";","/","+"});
    boolean hasDate = q.matches(".*\\b(20\\d{2}|\\d{4}-\\d{2}-\\d{2})\\b.*");
    double score = 0.4*Math.log(1+tokens) + 0.3*wh + 0.2*ops + 0.1*(hasDate?1:0);
    return score >= 1.2;
  }
  private static int count(String q, String[] arr) {
    int c=0; String low=q.toLowerCase();
    for (String s: arr) { if (low.contains(s.toLowerCase())) c++; }
    return c;
  }
}