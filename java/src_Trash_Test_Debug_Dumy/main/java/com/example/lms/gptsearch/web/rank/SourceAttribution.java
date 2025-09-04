package com.example.lms.gptsearch.web.rank;

/**
 * Record capturing source information for ranking.  Consists of the document
 * URL, the identifier of the provider that produced the result, and the
 * rank within that provider's result set (1‑based).
 */
public record SourceAttribution(String url, String providerId, int providerRank) {}
