package com.example.lms.service.rag.orchestrator;

import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.List;

/**
 * RagMetaSeedAdapter
 *
 * "기존 Context/RAG 결과 메타를 오케스트레이터에 자동 주입"하기 위한 얇은 어댑터.
 *
 * - ChatWorkflow 등 기존 파이프라인에서 이미 수집한 WEB/RAG(Content) 결과를
 * UnifiedRagOrchestrator.QueryRequest.seedWeb/seedVector 에 주입하면
 * 오케스트레이터가 동일한 Weighted-RRF + (Bi/DPP/ONNX) 재랭크를 재현할 수 있다.
 * - 이 어댑터는 side-effect 없이 request 객체만 채운다.
 */
public final class RagMetaSeedAdapter {

    private RagMetaSeedAdapter() {
    }

    /** PromptContext(web/rag) → QueryRequest seed로 주입 */
    public static void injectFromPromptContext(UnifiedRagOrchestrator.QueryRequest req, PromptContext ctx) {
        if (req == null || ctx == null) {
            return;
        }
        if (isEmpty(req.seedWeb)) {
            req.seedWeb = safeCopy(ctx.web());
        }
        if (isEmpty(req.seedVector)) {
            req.seedVector = safeCopy(ctx.rag());
        }
    }

    /**
     * TraceStore(스레드-로컬)에서 ChatWorkflow가 남긴 finalTopK를 seed로 주입.
     * - 키: finalWebTopK, finalVectorTopK
     */
    @SuppressWarnings("unchecked")
    public static void injectFromTraceStore(UnifiedRagOrchestrator.QueryRequest req) {
        if (req == null) {
            return;
        }
        if (isEmpty(req.seedWeb)) {
            Object web = TraceStore.get("finalWebTopK");
            List<Content> c = castContents(web);
            if (!isEmpty(c)) {
                req.seedWeb = c;
            }
        }
        if (isEmpty(req.seedVector)) {
            Object vec = TraceStore.get("finalVectorTopK");
            List<Content> c = castContents(vec);
            if (!isEmpty(c)) {
                req.seedVector = c;
            }
        }
    }

    private static boolean isEmpty(List<?> l) {
        return l == null || l.isEmpty();
    }

    private static List<Content> safeCopy(List<Content> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        List<Content> out = new ArrayList<>();
        for (Content c : src) {
            if (c != null) {
                out.add(c);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<Content> castContents(Object any) {
        if (!(any instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<Content> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Content c) {
                out.add(c);
            }
        }
        return out.isEmpty() ? null : out;
    }
}
