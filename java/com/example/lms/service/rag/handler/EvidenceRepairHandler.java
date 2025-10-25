package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.subject.SubjectResolver;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@RequiredArgsConstructor
public class EvidenceRepairHandler implements ContentRetriever {
    private static final Logger log = LoggerFactory.getLogger(EvidenceRepairHandler.class);

    private final WebSearchRetriever web;
    private final SubjectResolver subjectResolver;
    private final String domain;               // application.yml: retrieval.repair.domain
    private final String preferredDomains;     // application.yml: retrieval.repair.preferred-domains (comma-separated)

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null) return List.of();
        try {
            List<Content> items = web.retrieve(query);
            if (items == null || items.isEmpty()) return List.of();

            // 도메인/선호도메인 우대 정렬 (있을 때만)
            List<String> pref = parseCsv(preferredDomains);
            if ((domain == null || domain.isBlank()) && pref.isEmpty()) {
                return items;
            }
            return prioritize(items, pref, domain);
        } catch (Exception e) {
            log.warn("[Repair] retrieve failed; returning empty list", e);
            return List.of(); // 체인은 절대 크래시하지 않음
        }
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<Content> prioritize(List<Content> items, List<String> preferred, String domain) {
        if ((preferred == null || preferred.isEmpty()) && (domain == null || domain.isBlank())) return items;
        return items.stream()
                .sorted((a, b) -> Integer.compare(score(textOf(b), preferred, domain),
                        score(textOf(a), preferred, domain)))
                .collect(Collectors.toList());
    }

    private static String textOf(Content c) {
        if (c == null) return "";
        var ts = c.textSegment();
        return (ts != null) ? ts.text() : c.toString();
    }

    private static int score(String text, List<String> preferred, String domain) {
        int s = 0;
        if (domain != null && !domain.isBlank() && text.contains(domain)) s += 2;
        if (preferred != null) {
            for (String d : preferred) {
                if (!d.isBlank() && text.contains(d)) s += 1;
            }
        }
        return s;
    }
}