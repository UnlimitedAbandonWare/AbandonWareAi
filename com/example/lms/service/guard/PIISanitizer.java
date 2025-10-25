package com.example.lms.service.guard;
import java.util.regex.Pattern;
public final class PIISanitizer {
  private static final Pattern EMAIL=Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}");
  private static final Pattern PHONE=Pattern.compile("\\b\\d{2,3}-?\\d{3,4}-?\\d{4}\\b");
  public String sanitize(String s){
    if(s==null) return null;
    s=EMAIL.matcher(s).replaceAll("[redacted email]");
    s=PHONE.matcher(s).replaceAll("[redacted phone]");
    return s;
  }
}
