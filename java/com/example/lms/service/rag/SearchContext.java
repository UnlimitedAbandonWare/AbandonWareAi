package com.example.lms.service.rag;

import java.util.List;
import dev.langchain4j.rag.content.Content;

import java.util.stream.Collectors;  // Collectors 임포트

public class SearchContext {
    private List<Content> contents;
    private boolean isSuccess;

    public SearchContext(List<Content> contents, boolean isSuccess) {
        this.contents = contents;
        this.isSuccess = isSuccess;
    }

    public List<Content> getContents() {
        return contents;
    }

    public boolean isSuccess() {
        return isSuccess;
    }

    public static SearchContext fromSearchResults(List<String> snippets) {
        List<Content> contentList = snippets.stream()
                .map(Content::from)
                .collect(Collectors.toList());
        return new SearchContext(contentList, !contentList.isEmpty());
    }
}