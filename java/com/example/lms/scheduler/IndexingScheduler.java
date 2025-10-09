// 경로: src/main/java/com/example/lms/scheduler/IndexingScheduler.java
package com.example.lms.scheduler;

import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.VectorStoreService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.langchain4j.data.document.Metadata;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * 주기적으로 새 문서를 불러와 벡터 스토어에 추가하는 스케줄러입니다.
 *
 * <p>DocumentFetcher는 크롤러·로더 역할을 하는 인터페이스로, lastFetchTime 이후의
 * 새로운 문서를 반환합니다. 실제 구현에서는 DB 조회, 웹 크롤링, API 호출 등을
 * 통해 검색 결과를 가져올 수 있습니다.</p>
 *
 * <p>문서는 메타데이터(예: source, url, fetchedAt 등)를 포함한 상태로 생성하고,
 * 분할된 세그먼트는 임베딩 후 EmbeddingStore에 저장합니다.</p>
 */
@Component
@RequiredArgsConstructor
public class IndexingScheduler {
    private static final Logger log = LoggerFactory.getLogger(IndexingScheduler.class);

    private final EmbeddingModel              embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentFetcher             documentFetcher;
    private final MemoryReinforcementService  memorySvc;
    // Inject VectorStoreService to reuse buffering logic instead of direct addAll()
    private final VectorStoreService          vectorStoreService;

    /** 동시 실행 안전 – AtomicReference */
    private final java.util.concurrent.atomic.AtomicReference<LocalDateTime> lastFetchTime =
            new java.util.concurrent.atomic.AtomicReference<>(LocalDateTime.now().minusHours(1));

    /** ⏰ application.yml 의 indexing.cron(없으면 기본 매시간 0분) */
    @Scheduled(cron = "${indexing.cron:0 0 * * * *}")
    public void scheduleIndexing() {

        LocalDateTime from = lastFetchTime.get();
        log.info("[Indexing] 시작 – lastFetchTime={}", from);

        List<Document> newDocs = documentFetcher.fetchNewDocumentsSince(from);
        if (newDocs == null || newDocs.isEmpty()) {
            log.info("[Indexing] 추가할 문서가 없습니다.");
            return;
        }

        var splitter = DocumentSplitters.recursive(300, 50);
        List<TextSegment> segments = new ArrayList<>();
        java.util.Set<String> dedup = new java.util.HashSet<>();

        for (Document d : newDocs) {
            Metadata meta = Metadata.from(Map.of(
                    "source", "CRAWLER",
                    "fetchedAt", LocalDateTime.now().toString()
            ));
            Document withMeta = Document.from(d.text(), meta);
            for (TextSegment ts : splitter.split(withMeta)) {
                if (dedup.add(ts.text())) segments.add(ts);
            }
        }

        if (segments.isEmpty()) {
            log.info("[Indexing] 분할된 세그먼트가 없어 종료합니다.");
            return;
        }

        try {
            // Use VectorStoreService to enqueue segments for embedding & upload
            for (TextSegment seg : segments) {
                vectorStoreService.enqueue("0", seg.text());
            }
            // Trigger flush explicitly to upload immediately
            vectorStoreService.flush();
        } catch (Exception e) {
            log.warn("[Indexing] 벡터 스토어 적재 실패 – {}", e.getMessage());
        }

        // Reinforce snippets into long‑term memory with max score
        segments.forEach(seg -> memorySvc.reinforceWithSnippet(
                "0", null, seg.text(), "WEB", 1.0));

        lastFetchTime.set(LocalDateTime.now());
        log.info("[Indexing] 완료: {}개 세그먼트 저장", segments.size());

    }

    /**
     * 새 문서를 가져오는 인터페이스.
     *
     * <p>예: 뉴스 API, 게임 업데이트 크롤러, DB 등과 연동하여 구현하세요.</p>
     */
    public interface DocumentFetcher {
        /**
         * 주어진 시각 이후에 추가/수정된 문서를 반환합니다.
         *
         * @param lastFetchTime 마지막으로 문서를 수집한 시각
         * @return 새 문서 목록; 없으면 빈 리스트
         */
        List<Document> fetchNewDocumentsSince(LocalDateTime lastFetchTime);
    }
}