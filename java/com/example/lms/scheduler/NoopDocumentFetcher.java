package com.example.lms.scheduler;

import com.example.lms.scheduler.IndexingScheduler.DocumentFetcher;
import dev.langchain4j.data.document.Document;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;




/**
 * 실제 구현 전까지 애플리케이션 기동을 보장해 주는
 * 더미(Fallback) DocumentFetcher.
 */
@Component               // <-- 이 한 줄로 스프링 빈 등록
public class NoopDocumentFetcher implements DocumentFetcher {

    @Override
    public List<Document> fetchNewDocumentsSince(LocalDateTime lastFetchTime) {
        // shim: replace with DB or crawler logic in the future.
        return Collections.emptyList();
    }
}