package com.abandonware.ai.addons.synthesis;

import java.util.Map;



public record ContextItem(
        String id, String title, String snippet, String source, double score, int rank,
        Map<String, Object> meta
) {}