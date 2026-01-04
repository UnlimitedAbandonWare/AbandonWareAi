package com.example.lms.resume;
import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.resume.AtsRiskChecker
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.resume.AtsRiskChecker
role: config
*/
public class AtsRiskChecker {
  public static class Result { public double risk; public List<String> issues = new ArrayList<>(); }
  public Result check(String rendered){
    Result r = new Result();
    if (rendered.length()>4000){ r.issues.add("length-excess"); r.risk+=0.2; }
    if (rendered.matches("(?s).*\\|\\s*\\|.*")){ r.issues.add("tables-detected"); r.risk+=0.3; }
    if (rendered.matches("(?s).*[🖼️🎨⭐●■◆□○△▽▶◀❖✦✧✪✩✫✬✭✮✯].*")){ r.issues.add("icons"); r.risk+=0.2; }
    r.risk = Math.min(1.0, r.risk);
    return r;
  }
}