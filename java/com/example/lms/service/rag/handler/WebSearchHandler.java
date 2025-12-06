package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.search.TraceStore;
import com.example.lms.search.terms.SelectedTerms;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import lombok.extern.slf4j.Slf4j;


// Import TraceStore and SelectedTerms for LLM keyword selection metadata

@Slf4j @RequiredArgsConstructor
public class WebSearchHandler extends AbstractRetrievalHandler {
    private final WebSearchRetriever retriever;
    @Override protected boolean doHandle(Query q, List<Content> acc) {
        try {
            Query effectiveQuery = q;
            // Check for LLM selected terms stored in the TraceStore.  When present,
            // incorporate negative keywords, aliases and preferred domains into
            // the query string by appending exclusion operators and site filters.
            Object selObj = TraceStore.get("selectedTerms");
            if (selObj instanceof SelectedTerms selected) {
                String base = q != null ? q.text() : "";
                StringBuilder modified = new StringBuilder(base == null ? "" : base.trim());
                // Negative keywords: prefix with '-' to exclude undesired topics
                if (selected.getNegative() != null) {
                    for (String neg : selected.getNegative()) {
                        if (neg != null && !neg.isBlank()) {
                            modified.append(" -").append(neg.trim());
                        }
                    }
                }
                // Aliases: group with OR to broaden matching terms
                if (selected.getAliases() != null && !selected.getAliases().isEmpty()) {
                    List<String> aliases = selected.getAliases().stream()
                            .filter(a -> a != null && !a.isBlank())
                            .map(String::trim)
                            .toList();
                    if (!aliases.isEmpty()) {
                        modified.append(" (");
                        boolean first = true;
                        for (String alias : aliases) {
                            if (!first) modified.append(" OR ");
                            modified.append(alias);
                            first = false;
                        }
                        modified.append(")");
                    }
                }
                // Preferred domains: add site filters; join with OR for multiple domains
                if (selected.getDomains() != null && !selected.getDomains().isEmpty()) {
                    List<String> domains = selected.getDomains().stream()
                            .filter(d -> d != null && !d.isBlank())
                            .map(String::trim)
                            .toList();
                    if (!domains.isEmpty()) {
                        modified.append(" (");
                        boolean first = true;
                        for (String dom : domains) {
                            if (!first) modified.append(" OR ");
                            modified.append("site:").append(dom);
                            first = false;
                        }
                        modified.append(")");
                    }
                }
                String newText = modified.toString().trim();
                effectiveQuery = new Query(newText, q != null ? q.metadata() : null);
            }
            acc.addAll(retriever.retrieve(effectiveQuery));
        } catch (Exception e) {
            log.warn("[WebSearch] 실패 - 패스", e);
        }
        return true;
    }
}