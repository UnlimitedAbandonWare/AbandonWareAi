
package com.rc111.merge21.qa;

import com.rc111.merge21.llm.LlamaCppClient;
import com.rc111.merge21.rag.DynamicRetrievalHandlerChain;
import com.rc111.merge21.rag.SearchDoc;
import com.rc111.merge21.onnx.OnnxCrossEncoderReranker;
import com.rc111.merge21.guard.AnswerGuard;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatAnswerService {
    private final LlamaCppClient llama;
    private final DynamicRetrievalHandlerChain chain;
    private final OnnxCrossEncoderReranker cross;
    private final java.util.List<AnswerGuard> guards;

    public ChatAnswerService(LlamaCppClient llama,
                             DynamicRetrievalHandlerChain chain,
                             OnnxCrossEncoderReranker cross,
                             java.util.List<AnswerGuard> guards) {
        this.llama = llama;
        this.chain = chain;
        this.cross = cross;
        this.guards = guards;
    }

    public Answer answer(String query) {
        // 1) retrieve + fuse
        List<SearchDoc> docs = chain.retrieve(query, new DynamicRetrievalHandlerChain.Hint());
        // 2) rerank
        List<SearchDoc> reranked = cross.rerank(query, docs);
        // 3) draft with llama
        StringBuilder ctx = new StringBuilder();
        int limit = Math.min(5, reranked.size());
        String[] cites = new String[limit];
        for (int i=0;i<limit;i++) {
            SearchDoc d = reranked.get(i);
            cites[i] = d.source;
            ctx.append("[").append(i+1).append("] ").append(d.title).append("\n").append(d.snippet).append("\n");
        }
        String prompt = "Answer using the following sources:\n" + ctx + "\nQ: " + query + "\nA:";
        String text;
        try {
            text = llama.generate(prompt, 512, 0.7);
        } catch (Exception e) {
            text = "LLM error: " + e.getMessage();
        }
        Answer out = new Answer(text, cites, 0.5);
        // 4) guards
        for (AnswerGuard g : guards) out = g.filter(out);
        return out;
    }
}
