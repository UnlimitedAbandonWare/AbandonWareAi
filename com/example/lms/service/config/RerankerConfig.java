package com.example.lms.service.config; 
import com.example.lms.service.rerank.OnnxRerankerGuard;
public final class RerankerConfig { 
  public OnnxRerankerGuard guard(int max){ return new OnnxRerankerGuard(max); } 
}