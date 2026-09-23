package com.abandonware.ai.agent.service.rag.bm25;

import com.example.lms.trace.SafeRedactor;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class Bm25IndexHolder {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexHolder.class);

    private final Path indexPath;
    private final Directory dir;
    private final StandardAnalyzer analyzer;

    private volatile DirectoryReader reader;

    public Bm25IndexHolder() {
        this.indexPath = Paths.get(System.getProperty("user.dir"), "bm25_index");
        this.analyzer = new StandardAnalyzer();
        try {
            Files.createDirectories(indexPath);
            this.dir = FSDirectory.open(indexPath);

            // Ensure an index exists (even empty) so DirectoryReader.open won't explode.
            if (!DirectoryReader.indexExists(dir)) {
                try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
                    w.commit();
                }
            }

            this.reader = DirectoryReader.open(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to init BM25 index pathHash="
                    + SafeRedactor.hashValue(String.valueOf(indexPath)), e);
        }
    }

    public Path indexPath() {
        return indexPath;
    }

    public StandardAnalyzer analyzer() {
        return analyzer;
    }

    public IndexSearcher searcher() {
        refreshIfNeeded();
        return new IndexSearcher(reader);
    }

    public synchronized void refreshIfNeeded() {
        try {
            DirectoryReader newReader = DirectoryReader.openIfChanged(reader);
            if (newReader != null) {
                DirectoryReader old = reader;
                reader = newReader;
                try {
                    old.close();
                } catch (IOException closeError) {
                    log.debug("BM25 old reader close skipped. errorHash={} errorLength={}",
                            SafeRedactor.hashValue(messageOf(closeError)), messageLength(closeError));
                }
                log.info("BM25 index reader refreshed.");
            }
        } catch (IOException e) {
            log.warn("BM25 index refresh failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    @PreDestroy
    public void close() {
        try {
            reader.close();
        } catch (IOException closeError) {
            log.debug("BM25 reader close skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(closeError)), messageLength(closeError));
        }
        try {
            dir.close();
        } catch (IOException closeError) {
            log.debug("BM25 directory close skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(closeError)), messageLength(closeError));
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
