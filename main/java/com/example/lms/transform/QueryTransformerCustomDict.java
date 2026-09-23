package com.example.lms.transform;

import java.util.Map;

/**
 * Wrapper bean for the QueryTransformer custom dictionary.
 *
 * <p>Spring treats Map/List/Collection injection specially (as an aggregation of element beans),
 * which can silently ignore a {@code Map<String, String>} bean declared via {@code @Bean}.
 * Using a dedicated wrapper type makes the injection unambiguous.
 */
public record QueryTransformerCustomDict(Map<String, String> dict) {}
