package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import java.util.List;



/** 경량 1차 랭커 인터페이스 */
public interface LightWeightRanker {
    List<Content> rank(List<Content> candidates, String query, int limit);
}