package com.abandonware.ai.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Bm25Props {

    @Value("${bm25.enabled:true}")
    private boolean enabled = true;

    @Value("${bm25.topK:5}")
    private int topK = 5;

    @Value("${bm25.minSnippetChars:80}")
    private int minSnippetChars = 80;

    /**
     * 오프라인/로컬 인덱싱시 최대 문서 수 제한.
     * (너무 큰 TM 테이블을 그대로 인덱싱하는 것을 방지)
     */
    @Value("${bm25.maxDocs:20000}")
    private int maxDocs = 20000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMinSnippetChars() {
        return minSnippetChars;
    }

    public void setMinSnippetChars(int minSnippetChars) {
        this.minSnippetChars = minSnippetChars;
    }

    public int getMaxDocs() {
        return maxDocs;
    }

    public void setMaxDocs(int maxDocs) {
        this.maxDocs = maxDocs;
    }
}
