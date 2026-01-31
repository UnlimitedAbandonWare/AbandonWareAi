package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * PreconditionCheckHandler - detects scenarios where both web and vector
 * retrieval are disabled.  When such a configuration is encountered the
 * handler logs a warning and stops the retrieval chain to avoid
 * regenerations with no evidence.  This class follows the same
 * fail-soft contract as other retrieval handlers.
 */
public class PreconditionCheckHandler extends AbstractRetrievalHandler {
    private static final Logger log = LoggerFactory.getLogger(PreconditionCheckHandler.class);

    /** The Pinecone index name is blank when vector search is unavailable. */
    @Value("${pinecone.index.name:}")
    private String pineconeIndexName;

    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        // Null check
        if (query == null) {
            return true;
        }
        boolean allowWeb = mdBool(query.metadata(), "useWebSearch", false);
        String modeStr = mdString(query.metadata(), "searchMode", "AUTO");
        if ("OFF".equalsIgnoreCase(String.valueOf(modeStr))) {
            allowWeb = false;
        }
        boolean vectorDisabled = (pineconeIndexName == null || pineconeIndexName.isBlank());
        if (!allowWeb && vectorDisabled) {
            log.warn("[PreconditionCheckHandler] Web and vector search both disabled; aborting retrieval.");
            // returning false stops further handlers in the chain
            return false;
        }
        return true;
    }

    // Meta helpers duplicated to avoid external dependencies; these mirror the
    // helper methods in DefaultRetrievalHandlerChain.
    private static boolean mdBool(Object meta, String k, boolean defVal) {
        try {
            java.util.Map<String,Object> map = toMap(meta);
            Object v = map.get(k);
            if (v instanceof Boolean b) return b;
            if (v != null) return Boolean.parseBoolean(String.valueOf(v));
            return defVal;
        } catch (Exception e) { return defVal; }
    }
    private static String mdString(Object meta, String k, String defVal) {
        try {
            java.util.Map<String,Object> map = toMap(meta);
            Object v = map.get(k);
            return (v != null) ? String.valueOf(v) : defVal;
        } catch (Exception e) { return defVal; }
    }
    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> toMap(Object meta) {
        if (meta == null) return java.util.Map.of();
        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
            return (java.util.Map<String,Object>) m.invoke(meta);
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Method m2 = meta.getClass().getMethod("map");
                return (java.util.Map<String,Object>) m2.invoke(meta);
            } catch (Exception ex) {
                return java.util.Map.of();
            }
        } catch (Exception ex) {
            return java.util.Map.of();
        }
    }
}