package com.abandonware.ai.agent.service.rag.bm25;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.abandonware.ai.agent.config.Bm25Props;

/**
 * Incremental BM25 indexer.
 * This is a thin adapter that reads from the existing document store (not implemented here)
 * and writes to Lucene index at props.indexPath.
 * Plug your document source by replacing buildBatch() with real data.
 */
@Service
public class Bm25IndexService {

    private final Bm25Props props;

    public Bm25IndexService(Bm25Props props) {
        this.props = props;
    }

    public void rebuildIncremental() {
        if (!props.isEnabled()) return;
        try {
            Path path = Path.of(props.getIndexPath());
            if (!java.nio.file.Files.exists(path)) {
                java.nio.file.Files.createDirectories(path);
            }
            Analyzer analyzer = createAnalyzer(props.getAnalyzer());
            IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            try (IndexWriter writer = new IndexWriter(FSDirectory.open(path), iwc)) {
                // TODO: Replace with real incremental feed
                // Example placeholder docs ensure index structure exists.
                List<Document> docs = java.util.List.of(
                    makeDoc("demo-1", "Demo Title 1", "This is a placeholder content for BM25 index."),
                    makeDoc("demo-2", "Demo Title 2", "한국어 형태소 분석을 위한 노리 분석기 사용 예시입니다.")
                );
                for (Document d : docs) {
                    writer.updateDocument(new Term("id", d.get("id")), d);
                }
                writer.commit();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Document makeDoc(String id, String title, String content) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new TextField("content", content, Field.Store.YES));
        return doc;
    }

private Analyzer createAnalyzer(String name) {
    if (!"nori".equalsIgnoreCase(name)) {
        return new StandardAnalyzer();
    }
    try {
        Class<?> clazz = Class.forName("org.apache.lucene.analysis.ko.NoriAnalyzer");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
        Object instance = ctor.newInstance();
        return (Analyzer) instance;
    } catch (Throwable ignore) { /* analyzer not on classpath */ }
    return new StandardAnalyzer();
}

}