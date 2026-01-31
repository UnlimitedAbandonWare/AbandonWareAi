package com.example.lms.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import org.apache.lucene.store.ByteBuffersDirectory;   // ✅ 새 디렉터리

@Service
public class KeyTermMiner {
    private static final Logger log = LoggerFactory.getLogger(KeyTermMiner.class);

    private static final int DEFAULT_TOP_N = 3;
    private final Analyzer analyzer = new KoreanAnalyzer();

    /** 스니펫 리스트 → BM25 상위 N 단어 반환 (1글자 제외) */
    public List<String> topKeyTerms(List<String> snippets, int topN) {
        if (snippets == null || snippets.isEmpty()) return List.of();
        try (var ram = new ByteBuffersDirectory()) {   // AutoCloseable 그대로
            IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
            cfg.setSimilarity(new BM25Similarity());
            try (IndexWriter w = new IndexWriter(ram, cfg)) {
                for (String s : snippets) {
                    Document d = new Document();
                    d.add(new TextField("body", s, TextField.Store.NO));
                    w.addDocument(d);
                }
            }
            IndexReader r = DirectoryReader.open(ram);
            TermsEnum te;
            Map<String, Long> tf = new HashMap<>();

            for (int i = 0; i < r.maxDoc(); i++) {
                Terms terms = r.getTermVector(i, "body");
                if (terms == null) continue;
                te = terms.iterator();
                while (te.next() != null) {
                    String term = te.term().utf8ToString();
                    if (term.length() <= 1) continue;
                    tf.merge(term, te.totalTermFreq(), Long::sum);
                }
            }
            return tf.entrySet().stream()
                    .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
                    .limit(topN <= 0 ? DEFAULT_TOP_N : topN)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("[KeyTermMiner] failed: {}", e.toString());
            return List.of();
        }
    }
}