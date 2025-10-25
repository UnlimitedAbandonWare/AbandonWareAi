package guard;

import java.util.regex.Pattern;

public class PIISanitizer {
  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE = Pattern.compile("(010|011|016|017|018|019)[- ]?\\d{3,4}[- ]?\\d{4}");
  private static final Pattern SSN   = Pattern.compile("\\d{6}-?\\d{7}");

  public String filter(String s){
    if (s == null) return null;
    s = EMAIL.matcher(s).replaceAll("[email]");
    s = PHONE.matcher(s).replaceAll("[phone]");
    s = SSN.matcher(s).replaceAll("[id]");
    return s;
  }
}