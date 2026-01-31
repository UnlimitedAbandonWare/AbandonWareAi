package com.example.lms.service.rag.retriever;

import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * BM25 기반 로컬 검색기의 얇은 래퍼.
 *
 * <p>정식 구현은 {@link LocalBm25Retriever} 에 있으며,
 * 이 클래스는 과거 API와의 호환을 위해 남겨둔 alias 입니다.</p>
 */
@Deprecated // backward-compatible alias, 새 코드는 LocalBm25Retriever 사용
public class Bm25LocalRetriever {

    private final LocalBm25Retriever delegate = new LocalBm25Retriever();

    private boolean enabled = Boolean.parseBoolean(System.getProperty("retrieval.bm25.enabled", "false"));
    @SuppressWarnings("FieldCanBeLocal")
    private String indexPath = System.getProperty("bm25.index.path", "");
    private int topK = Integer.parseInt(System.getProperty("bm25.topK", "50"));

    /**
     * Search local index. When disabled, returns an empty result.
     * 실제 인덱스 구성은 {@link LocalBm25Retriever} 가 담당한다.
     */
    public WebSearchResult search(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return new WebSearchResult("bm25", List.of());
        }
        List<LocalBm25Retriever.Doc> docs = delegate.topK(query, topK);
        List<WebDocument> webDocs = new ArrayList<>();
        for (LocalBm25Retriever.Doc d : docs) {
            WebDocument wd = new WebDocument();
            wd.setTitle(d.id);
            wd.setSnippet(d.text);
            wd.setUrl(null);
            wd.setCredibility(null);
            wd.setTimestamp((Instant) null);
            webDocs.add(wd);
        }
        return new WebSearchResult("bm25", webDocs);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getTopK() {
        return topK;
    }

    public String getIndexPath() {
        return indexPath;
    }

    /**
     * 간단한 인덱스 추가 헬퍼.
     * 기존 코드가 존재하지 않는 경우를 대비해 공개해 둔다.
     */
    public void addDocument(String id, String text) {
        delegate.add(new LocalBm25Retriever.Doc(id, text));
    }
}
