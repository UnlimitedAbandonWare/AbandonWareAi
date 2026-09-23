package com.example.lms.config.rag;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;
import com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;




/**
 * KnowledgeGraphHandler瑜??숈쟻 泥댁씤???덉쟾?섍쾶 寃곗꽑?섎뒗 援ъ꽦.
 * - 以묐났 ?쎌엯 諛⑹?
 * - ?몃뜳??吏??媛?? retrieval.kg.order-index (湲곕낯 0 = 理쒖쟾??
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
        List<Object> steps = chain.getSteps();
        if (steps == null) return;
        // ?대? ?ы븿?섏뼱 ?덉쑝硫??⑥뒪
        for (Object s : steps) {
            if (s.getClass().getName().equals(kg.getClass().getName())) {
                return;
            }
        }
        // DynamicRetrievalHandlerChain owns the typed KG slot; do not mutate private handler lists here.
    }

}
