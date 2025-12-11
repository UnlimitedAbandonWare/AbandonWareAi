package com.example.lms.config.rag;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;
import com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;




/**
 * KnowledgeGraphHandler를 동적 체인에 안전하게 결선하는 구성.
 * - 중복 삽입 방지
 * - 인덱스 지정 가능: retrieval.kg.order-index (기본 0 = 최전단)
 */
@Configuration
@ConditionalOnProperty(prefix = "retrieval.kg", name = "enabled", havingValue = "true", matchIfMissing = false)
public class KgStepRegistrar {

    private final ObjectProvider<DynamicRetrievalHandlerChain> chainProvider;
    private final ObjectProvider<KnowledgeGraphHandler> kgProvider;
    private final KgStepRegistrarProps props;

    public KgStepRegistrar(ObjectProvider<DynamicRetrievalHandlerChain> chainProvider,
                           ObjectProvider<KnowledgeGraphHandler> kgProvider,
                           KgStepRegistrarProps props) {
        this.chainProvider = chainProvider;
        this.kgProvider = kgProvider;
        this.props = props;
    }

    @PostConstruct
    public void register() {
        DynamicRetrievalHandlerChain chain = chainProvider.getIfAvailable();
        KnowledgeGraphHandler kg = kgProvider.getIfAvailable();
        if (chain == null || kg == null) {
            return;
        }
        List<Object> steps = extractSteps(chain); // 체인 내부 컬렉션을 노출하는 간단 API가 있다고 가정
        if (steps == null) return;
        // 이미 포함되어 있으면 패스
        for (Object s : steps) {
            if (s.getClass().getName().equals(kg.getClass().getName())) {
                return;
            }
        }
        int idx = Math.max(0, props.getOrderIndex());
        if (idx >= steps.size()) {
            steps.add(kg);
        } else {
            steps.add(idx, kg);
        }
    }


    @SuppressWarnings({"unchecked","rawtypes"})
    private static java.util.List<Object> extractSteps(com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain chain) {
        try {
            java.util.List<Object> s = (java.util.List<Object>) (java.util.List) chain.getSteps();
            if (s != null) return s;
        } catch (Throwable ignore) {}
        // Reflective fallback
        for (String m : new String[]{"getSteps","getHandlers","snapshot","asList"}) {
            try {
                java.lang.reflect.Method md = chain.getClass().getMethod(m);
                Object v = md.invoke(chain);
                if (v instanceof java.util.List) return (java.util.List<Object>) v;
            } catch (ReflectiveOperationException ignored) {}
        }
        for (String f : new String[]{"steps","handlers","chain"}) {
            try {
                java.lang.reflect.Field fld = chain.getClass().getDeclaredField(f);
                fld.setAccessible(true);
                Object v = fld.get(chain);
                if (v instanceof java.util.List) return (java.util.List<Object>) v;
            } catch (ReflectiveOperationException ignored) {}
        }
        return java.util.Collections.emptyList();
    }

}