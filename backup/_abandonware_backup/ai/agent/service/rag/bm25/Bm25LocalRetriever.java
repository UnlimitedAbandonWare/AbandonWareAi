package com.abandonware.ai.agent.service.rag.bm25;


import com.abandonware.ai.agent.integrations.index.Bm25LocalIndex;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.analysis.Analyzer;
import org.springframework.stereotype.Component;
import com.abandonware.ai.service.rag.model.ContextSlice;

import java.util.ArrayList;
import java.util.List;

import com.abandonware.ai.agent.config.Bm25Props;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.service.rag.bm25.Bm25LocalRetriever
 * Role: config
 * Dependencies: com.abandonware.ai.agent.integrations.index.Bm25LocalIndex, com.abandonware.ai.service.rag.model.ContextSlice, com.abandonware.ai.agent.config.Bm25Props
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.service.rag.bm25.Bm25LocalRetriever
role: config
*/
public class Bm25LocalRetriever {

    private final Bm25Props props;
    private final Bm25IndexHolder holder;

    public Bm25LocalRetriever(Bm25Props props, Bm25IndexHolder holder) {
        this.props = props;
        this.holder = holder;
    }

    
    public List<ContextSlice> retrieve(String query, int k) {
        try {
            if (!props.isEnabled() || holder.searcher() == null) return java.util.List.of();
            k = (k<=0)?props.getTopK():k;
            Analyzer analyzer = holder.analyzer();
            QueryParser parser = new QueryParser("content", analyzer);
            Query q = parser.parse(QueryParser.escape(query));
            TopDocs td = holder.searcher().search(q, k);
            List<ContextSlice> out = new java.util.ArrayList<>();
            for (ScoreDoc sd : td.scoreDocs) {
                Document d = holder.searcher().doc(sd.doc);
                ContextSlice cs = new ContextSlice();
                cs.setId(d.get("id"));
                cs.setTitle(d.get("title"));
                cs.setSnippet(snippet(d.get("content"), props.getMinSnippetChars()));
                cs.setSource("bm25");
                cs.setScore((double) sd.score);
                cs.setRank(out.size()+1);
                out.add(cs);
            }
            return out;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private String snippet(String content, int minChars) {
        if (content == null) return "";
        if (content.length() <= minChars) return content;
        return content.substring(0, Math.min(minChars, content.length()));
    }
}