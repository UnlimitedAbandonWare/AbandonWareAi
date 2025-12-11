package com.abandonware.ai.agent.service.rag.bm25;

import jakarta.annotation.PostConstruct;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.index.IndexReader;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import com.abandonware.ai.agent.config.Bm25Props;

@Component
public class Bm25IndexHolder {

    private final Bm25Props props;
    private IndexReader reader;
    private IndexSearcher searcher;
    private Analyzer analyzer;

    public Bm25IndexHolder(Bm25Props props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws IOException {
        Path p = Path.of(props.getIndexPath());
        if (!java.nio.file.Files.exists(p)) {
            java.nio.file.Files.createDirectories(p);
        }
        this.analyzer = createAnalyzer(props.getAnalyzer());
        // Lazily open reader when index exists to avoid startup failure
        if (DirectoryReader.indexExists(FSDirectory.open(p))) {
            this.reader = DirectoryReader.open(FSDirectory.open(p));
            this.searcher = new IndexSearcher(reader);
        }
    }

    public IndexSearcher searcher() throws IOException {
        if (searcher == null && reader != null) {
            searcher = new IndexSearcher(reader);
        }
        return searcher;
    }

    public Analyzer analyzer() {
        return analyzer;
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