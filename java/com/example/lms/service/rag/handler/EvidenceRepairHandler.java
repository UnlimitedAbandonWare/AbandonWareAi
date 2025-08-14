// src/main/java/com/example/lms/service/rag/handler/EvidenceRepairHandler.java
package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.subject.SubjectResolver;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 앞선 단계에서 증거가 빈약할 때, 주제 앵커를 강제 포함한 1회 재검색으로 보강.
 */
@Slf4j
@RequiredArgsConstructor
public class EvidenceRepairHandler extends AbstractRetrievalHandler {

    private final WebSearchRetriever web;
    private final SubjectResolver subjectResolver;
    private final String domain;
    private final String preferredDomainsCsv; // 예: "*.ac.kr,*.go.kr,*.or.kr"

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            String text = q.text() == null ? "" : q.text().trim();
            String subject = subjectResolver.resolve(text, domain).orElse("");
            if (subject.isBlank()) return true;

            // 앵커 고정 쿼리 구성
            String anchored = subject + " " + text;
            if (preferredDomainsCsv != null && !preferredDomainsCsv.isBlank()) {
                for (String d : preferredDomainsCsv.split(",")) {
                    String dom = d.trim();
                    if (!dom.isEmpty()) anchored += " site:" + dom;
                }
            }
            acc.addAll(web.retrieve(Query.from(anchored)));
        } catch (Exception e) {
            log.debug("[EvidenceRepair] skip: {}", e.toString());
        }
        return false; // 말단에서 종료
    }
}
