package com.example.lms.service.planner.dto;
import java.util.List;
public class SelfAskPlan {
  private final String original;
  private final List<String> subQuestions;
  public SelfAskPlan(String original, List<String> subQuestions){
    this.original = original; this.subQuestions = subQuestions;
  }
  public String getOriginal(){ return original; }
  public List<String> getSubQuestions(){ return subQuestions; }
  @Override public String toString(){ return "SelfAskPlan{original='"+original+"', subQuestions="+subQuestions+"}"; }
}
