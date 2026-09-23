package guard;
public class PIISanitizer {
  public String clean(String text){
    if (text == null) return null;
    String out = text.replaceAll("\\b\\d{3}-\\d{3,4}-\\d{4}\\b", "[phone]");
    out = out.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+", "[email]");
    return out;
  }
}