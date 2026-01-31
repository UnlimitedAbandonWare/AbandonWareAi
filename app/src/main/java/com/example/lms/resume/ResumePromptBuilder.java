package com.example.lms.resume;
import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.resume.ResumePromptBuilder
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.resume.ResumePromptBuilder
role: config
*/
public class ResumePromptBuilder {
  public static class Context {
    public String jdSnapshot;
    public List<String> evidence; // normalized citations
    public Map<String,Object> profile;
  }
  public String buildKo(Context c){
    StringBuilder sb=new StringBuilder();
    sb.append("## JD Snapshot\n").append(c.jdSnapshot).append("\n\n");
    sb.append("## Korean Resume - 1 Page\n");
    sb.append("- 경력요약, 핵심역량, 성과(숫자%) 중심\n- 표/아이콘/두열 금지, ATS 호환\n\n");
    sb.append("## English Resume - 1 Page\n");
    sb.append("- Summary, Key Skills, Impact with metrics\n\n");
    sb.append("## ATS Risk Checklist\n- No tables/icons/two-columns\n- Contact once, 1 page\n");
    return sb.toString();
  }
}