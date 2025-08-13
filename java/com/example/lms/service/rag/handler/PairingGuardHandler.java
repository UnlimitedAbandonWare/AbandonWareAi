// src/main/java/com/example/lms/service/rag/handler/PairingGuardHandler.java
package com.example.lms.service.rag.handler;

import com.example.lms.prompt.PromptContext;
import com.example.lms.search.SmartQueryPlanner;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.rag.subject.SubjectResolver;
import com.example.lms.service.rag.subject.SubjectResolver.Subject;
import com.example.lms.service.rag.policy.PairingPolicy;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class PairingGuardHandler extends AbstractRetrievalHandler {

    private final QueryContextPreprocessor preprocessor;
    private final SubjectResolver subjectResolver;
    private final SmartQueryPlanner planner;
    private final PairingPolicy policy;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        String intent = preprocessor.inferIntent(q.text());
        if (!"PAIRING".equalsIgnoreCase(intent)) {
            return true; // 다른 핸들러로 패스
        }
        Subject s = subjectResolver.resolve(q.text());
        // 주어 앵커가 들어간 변형 쿼리로 상위 핸들러가 실제 검색 수행
        List<String> anchored = planner.planAnchored(q.text(), s.primaryKo(), s.aliasEn(), null, 3);
        // 컨텍스트(프롬프트)용 메타 구성
        PromptContext ctx = PromptContext.builder()
                .intent("PAIRING")
                .subjectName((s.primaryKo() + (s.aliasEn().isBlank() ? "" : " / " + s.aliasEn())).trim())
                .trustedHosts(policy.trustedHosts())
                .minEvidence(policy.minEvidence())
                .build();
        // 체인 컨텍스트에 저장하고 다음으로 넘길 수 있도록 확장(프로젝트별 구현에 맞춰 연결)
        log.debug("[PairingGuard] intent=PAIRING, anchoredQueries={}", anchored);
        return true;
    }
}
