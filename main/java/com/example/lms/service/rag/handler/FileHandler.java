package com.example.lms.service.rag.handler;

import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * A retrieval handler that emits a lightweight marker when a file context is
 * present on the query metadata. This handler does not actually retrieve
 * content from any source; instead it appends a shim to the
 * accumulator to inform downstream components (such as the evidence guard)
 * that an uploaded file exists. The presence of a file context should
 * generally suppress web search and RAG retrieval, giving priority to the
 * uploaded content. The handler always returns {@code true} to allow the
 * chain to continue executing.
 */
public class FileHandler extends AbstractRetrievalHandler {
    private static final Logger log = LoggerFactory.getLogger(FileHandler.class);
    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        try {
            if (query != null && query.metadata() != null) {
                Map<String, Object> map = com.example.lms.util.MetadataUtils.toMap(query.metadata());

                Object flag = map.get("hasFileContext");
                boolean hasFile =
                        Boolean.TRUE.equals(flag) ||
                                (flag instanceof String s && "true".equalsIgnoreCase(s));

                if (hasFile) {
                    var seg = TextSegment.from(
                            "Uploaded file content present",
                            Metadata.from(Map.of("source", "FILE", "hasFileContext", true))
                    );
                    accumulator.add(Content.from(seg));
                }

            }
        } catch (Exception e) {
            // Fail soft: do not break the chain
            log.warn("[AWX][rag][handler] file failed failureReason={} errorType={} queryHash12={} queryLength={}",
                    "file-handler-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(query == null ? null : query.text()),
                    query == null || query.text() == null ? 0 : query.text().length());
        }
        return true;
    }
}
