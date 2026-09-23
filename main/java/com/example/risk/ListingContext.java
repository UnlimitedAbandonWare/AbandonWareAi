package com.example.risk;

import dev.langchain4j.rag.content.Content;
import java.util.List;



/**
 * Context record holding candidate Content signals for risk evaluation.
 */
public record ListingContext(List<Content> signals) {}