// src/main/java/com/example/lms/service/rag/handler/PairingGuardHandler.java
package com.example.lms.service.rag.handler;

import com.example.lms.prompt.PromptContext;
import com.example.lms.search.SmartQueryPlanner;
import com.example.lms.service.rag.policy.PairingPolicy;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.subject.SubjectResolver;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@RequiredArgsConstructor
public class PairingGuardHandler extends AbstractRetrievalHandler {
    private static final Logger log = LoggerFactory.getLogger(PairingGuardHandler.class);

    private final QueryContextPreprocessor preprocessor;
    private final SubjectResolver subjectResolver;   // ← 통합 버전으로 교체
    private final SmartQueryPlanner planner;
    private final PairingPolicy policy;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        String queryText = q.text();
        String intent = preprocessor.inferIntent(queryText);
        if (!"PAIRING".equalsIgnoreCase(intent)) {
            return true; // 다른 핸들러로 패스
        }

        // 새 Resolver 시그니처에 맞게 domain 먼저 추출
        String domain = preprocessor.detectDomain(queryText);
        String subject = subjectResolver.resolve(queryText, domain).orElse("");

        // 주어 앵커를 포함한 변형 쿼리 생성
        // 이전 버전은 (orig, primaryKo, aliasEn, null, 3) 형태였음.
        // alias가 없으면 빈 문자열 전달(메서드가 null 금지일 수 있어 안전)
        List<String> anchored = planner.planAnchored(queryText, subject, "", null, 3);

        // 프롬프트 컨텍스트에 intent 등 필요한 정보만 세팅
        PromptContext ctx = PromptContext.builder()
                .intent("PAIRING")
                // .minEvidence(policy.minEvidence()) // 메서드가 없으면 주석 유지
                .build();

        log.debug("[PairingGuard] intent=PAIRING, domain={}, subject='{}', anchored={}",
                domain, subject, anchored);

        // 여기서 실제 검색 실행/컨텍스트 주입은 상위 체인 설계에 맞춰 계속/* ... */
        return true;
    }
}