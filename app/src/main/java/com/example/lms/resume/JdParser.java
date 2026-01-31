package com.example.lms.resume;
import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.resume.JdParser
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.resume.JdParser
role: config
*/
public class JdParser {
  public static class Snapshot {
    public Set<String> skills = new LinkedHashSet<>();
    public String role; public String domain; public List<String> tags = new ArrayList<>();
  }
  public Snapshot extract(String jd){
    Snapshot s = new Snapshot();
    for (String tok : jd.toLowerCase().split("\\W+")){
      if (tok.matches("java|python|spring|onnx|rag|mcp|opencv")) s.skills.add(tok);
    }
    if (jd.toLowerCase().contains("agent")||jd.toLowerCase().contains("rag")) s.domain = "LLM/RAG";
    return s;
  }
}