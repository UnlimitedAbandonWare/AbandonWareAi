package com.example.lms.gptsearch.web.rank;

import com.example.lms.gptsearch.web.dto.WebDocument;
import java.util.List;

/**
 * SPI for ranking web search results.  Implementations receive a list of
 * search results potentially containing duplicates and must return a
 * deduplicated, sorted list according to the desired ranking strategy.
 */
public interface WebRankingService {
  /**
   * Rank the given documents.
   *
   * @param docs fanout results including duplicates
   * @param atts attribution information for each document (url, provider id, provider rank)
   * @param topK number of documents to return (0 or less for no limit)
   * @return ranked, deduplicated list of documents
   */
  List<WebDocument> rank(List<WebDocument> docs, List<SourceAttribution> atts, int topK);
}
