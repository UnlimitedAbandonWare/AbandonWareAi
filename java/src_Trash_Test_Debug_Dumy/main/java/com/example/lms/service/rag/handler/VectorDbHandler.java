package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Slf4j @RequiredArgsConstructor
public class VectorDbHandler extends AbstractRetrievalHandler {
    private final LangChainRAGService ragSvc;
    private final String indexName;
    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            // Guard: skip vector retrieval when the index name is blank or null.
            if (indexName == null || indexName.isBlank()) {
                log.debug("[VectorDB] indexName is blank — skipping vector retrieval (fail-soft).");
                return true;
            }
            ContentRetriever pine = ragSvc.asContentRetriever(indexName);
            if (pine == null) {
                log.debug("[VectorDB] retriever is null — skipping (fail-soft).");
                return true;
            }
            // Perform the primary vector retrieval
            List<Content> primary = pine.retrieve(q);
            if (primary != null) {
                acc.addAll(primary);
            }
            // When no primary results are returned attempt a fallback query using
            // the optional fallback index.  The fallback index name is read from
            // a system property (pinecone.fallback-index) or environment variable
            // (PINECONE_FALLBACK_INDEX).  When unset or blank no fallback occurs.
            boolean usedFallback = false;
            if (primary == null || primary.isEmpty()) {
                String fallback = System.getProperty("pinecone.fallback-index", System.getenv().getOrDefault("PINECONE_FALLBACK_INDEX", ""));
                if (fallback != null && !fallback.isBlank()) {
                    try {
                        ContentRetriever alt = ragSvc.asContentRetriever(fallback);
                        if (alt != null) {
                            log.info("[VectorDB] primary empty → fallback index={}", fallback);
                            List<Content> backup = alt.retrieve(q);
                            if (backup != null) {
                                acc.addAll(backup);
                            }
                            usedFallback = true;
                        }
                    } catch (Exception e) {
                        // suppress fallback errors; fail-soft
                        log.warn("[VectorDB] fallback retrieval failed", e);
                    }
                }
            }
        } catch (Exception e) {
            // fail-soft: log the error but allow the chain to continue
            log.warn("[VectorDB] 실패 – fail-soft", e);
            return true;
        }
        // Fast‑path: when at least two high similarity vector results are
        // retrieved assume the vector evidence is sufficient and skip
        // subsequent retrieval stages.  When at least three results exist
        // regardless of score, also skip.  This evaluation occurs after
        // adding both primary and fallback results to the accumulator.
        try {
            int highScoreCount = 0;
            if (acc != null) {
                for (Content c : acc) {
                    double score = 1.0;
                    try {
                        var metaMethod = c.getClass().getMethod("metadata");
                        Object metaObj = metaMethod.invoke(c);
                        if (metaObj instanceof java.util.Map<?, ?> m) {
                            Object sObj = m.get("score");
                            if (sObj != null) {
                                score = Double.parseDouble(sObj.toString());
                            }
                        }
                    } catch (Exception ignore) {
                        // ignore and use default score
                    }
                    if (score >= 0.60) {
                        highScoreCount++;
                        if (highScoreCount >= 2) {
                            log.debug("[VectorDB] similarity fast‑path activated – skipping subsequent handlers");
                            return false;
                        }
                    }
                }
                // Fallback: if at least three vector results exist, skip subsequent stages as before
                if (acc.size() >= 3) {
                    log.debug("[VectorDB] size‑based fast‑path activated – skipping subsequent handlers");
                    return false;
                }
            }
        } catch (Exception ignore) {
            // ignore any errors while evaluating fast‑path criteria
        }
        // Continue to the next handler by default.
        return true;
    }
}
