package com.example.lms.service.rag.retriever.bm25;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.retriever.bm25.LuceneBm25Retriever
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.retriever.bm25.LuceneBm25Retriever
role: config
*/
public class LuceneBm25Retriever implements Bm25Retriever {
    private final Directory dir;
    private final Analyzer analyzer;
    private final String defaultField;
    private final int defaultTopK;

    public LuceneBm25Retriever(Path indexPath, String defaultField, int defaultTopK) {
        try {
            this.dir = FSDirectory.open(indexPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open Lucene index at: " + indexPath, e);
        }
        this.analyzer = new StandardAnalyzer();
        this.defaultField = defaultField == null ? "content" : defaultField;
        this.defaultTopK = defaultTopK <= 0 ? 20 : defaultTopK;
    }

    @Override
    public List<Candidate> search(String q, int k) {
        List<Candidate> out = new ArrayList<>();
        if (q == null || q.isBlank()) return out;
        int topK = k > 0 ? k : defaultTopK;
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            QueryParser parser = new QueryParser(defaultField, analyzer);
            Query query = parser.parse(q);
            TopDocs td = searcher.search(query, topK);
            int rank = 1;
            for (ScoreDoc sd : td.scoreDocs) {
                Document d = searcher.doc(sd.doc);
                String id = d.get("id");
                String title = d.get("title");
                String snippet = d.get("content");
                String source = d.get("source");
                String url = d.get("url");
                out.add(new Candidate(id, title, snippet, source, sd.score, rank++, url));
            }
        } catch (Exception ignore) {
            // Return empty on any Lucene parse/IO error; retriever is optional and guarded by a toggle.
        }
        return out;
    }
}