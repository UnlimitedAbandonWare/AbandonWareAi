package strategy.guard;

import java.util.regex.*;
public class PiiSanitizer {
  private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
  private static final Pattern PHONE = Pattern.compile("(01[0-9]-?\\d{3,4}-?\\d{4})");
  public String sanitize(String text) {
    if (text==null) return null;
    String t = EMAIL.matcher(text).replaceAll("[email masked]");
    t = PHONE.matcher(t).replaceAll("[phone masked]");
    return t;
  }
}