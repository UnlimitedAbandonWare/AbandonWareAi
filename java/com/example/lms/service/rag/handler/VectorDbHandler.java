package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import lombok.extern.slf4j.Slf4j;



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
            acc.addAll(pine.retrieve(q));
        } catch (Exception e) {
            // fail-soft: log the error but allow the chain to continue
            log.warn("[VectorDB] 실패 – fail-soft", e);
            return true;
        }
        // Fast‑path: when at least three vector results are retrieved assume that the
        // vector evidence alone is sufficient and skip subsequent retrieval stages.
        try {
            // Fast‑path: when there are at least two high‑similarity vector results
            // we assume the vector evidence is sufficient and skip subsequent stages.
            // The similarity threshold (0.60) and required count (>=2) follow the
            // MOE guidelines.  We attempt to extract a score from each content's
            // metadata when possible; if unavailable we conservatively treat the
            // result as a match.  Any exceptions during extraction are ignored.
            int highScoreCount = 0;
            if (acc != null) {
                for (Content c : acc) {
                    double score = 1.0;
                    try {
                        // Try to extract a score from content metadata
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