package com.abandonware.ai.agent.service.rag.bm25;

import com.abandonware.ai.agent.config.Bm25Props;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import jakarta.annotation.PostConstruct;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class Bm25IndexService {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexService.class);

    private final Bm25Props props;
    private final Bm25IndexHolder holder;
    private final ObjectProvider<TranslationMemoryRepository> tmRepoProvider;

    @Value("${bm25.autoIndex:false}")
    private boolean autoIndex;

    public Bm25IndexService(Bm25Props props,
                            Bm25IndexHolder holder,
                            ObjectProvider<TranslationMemoryRepository> tmRepoProvider) {
        this.props = props;
        this.holder = holder;
        this.tmRepoProvider = tmRepoProvider;
    }

    @PostConstruct
    public void maybeAutoIndex() {
        if (!autoIndex) {
            return;
        }
        try {
            rebuildFromTranslationMemory();
        } catch (Exception e) {
            // 오프라인 환경/DB 미구성 환경에서도 서비스 기동이 죽지 않도록 fail-soft.
            log.warn("BM25 autoIndex skipped: {}", e.toString());
        }
    }

    /**
     * TranslationMemory 테이블을 기반으로 BM25 인덱스를 재생성합니다.
     * (오프라인/로컬 테스트용 최소 구현)
     */
    public void rebuildFromTranslationMemory() {
        TranslationMemoryRepository repo = tmRepoProvider.getIfAvailable();
        if (repo == null) {
            log.info("TranslationMemoryRepository bean not found; BM25 index rebuild skipped.");
            return;
        }

        int maxDocs = Math.max(1, props.getMaxDocs());
        int pageSize = Math.min(1000, maxDocs);

        Sort sort = Sort.by(Sort.Order.desc("lastUsedAt"), Sort.Order.desc("id"));

        Path path = holder.indexPath();
        log.info("Rebuilding BM25 index at {} (maxDocs={})", path, maxDocs);

        try (var dir = FSDirectory.open(path);
             var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer())
                     .setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {

            int total = 0;
            int page = 0;

            while (total < maxDocs) {
                int remaining = maxDocs - total;
                int size = Math.min(pageSize, remaining);

                var pageable = PageRequest.of(page, size, sort);
                var batch = repo.findAll(pageable);

                if (batch.isEmpty()) {
                    break;
                }

                for (TranslationMemory tm : batch) {
                    Document doc = new Document();

                    String id = safe(tm.getSourceHash(), "tm:" + tm.getId());
                    String title = firstNonBlank(tm.getQuery(), tm.getSource(), "translation_memory");
                    String body = firstNonBlank(tm.getCorrected(), tm.getContent(), tm.getSource(), tm.getQuery(), "");

                    doc.add(new StringField("id", id, Field.Store.YES));
                    doc.add(new StringField("title", title, Field.Store.YES));
                    doc.add(new TextField("content", body, Field.Store.YES));

                    writer.addDocument(doc);
                    total++;

                    if (total >= maxDocs) {
                        break;
                    }
                }

                if (!batch.hasNext()) {
                    break;
                }

                page++;
            }

            writer.commit();
            log.info("BM25 index rebuild done. indexedDocs={}", total);
        } catch (Exception e) {
            throw new RuntimeException("Failed to rebuild BM25 index", e);
        }

        // Refresh the in-memory reader/searcher so retrieval uses the new index.
        holder.refreshIfNeeded();
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
