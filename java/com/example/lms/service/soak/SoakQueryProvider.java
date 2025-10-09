package com.example.lms.service.soak;

import java.util.List;
import java.util.Optional;

/**
 * Interface for sampling a set of queries for soak tests.  Implementations
 * may source queries from vector stores, embeddings or any other source.
 * The optional topic hint allows the provider to filter queries by
 * domain (e.g. genshin vs default) when supported.  When the topic is
 * empty the provider should return queries from all domains.
 */
public interface SoakQueryProvider {
    List<String> sample(int limit, Optional<String> topic);
}