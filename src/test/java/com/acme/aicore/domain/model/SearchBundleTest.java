package com.acme.aicore.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchBundleTest {

    @Test
    void mergeHandlesNullInputs() {
        assertThat(SearchBundle.merge(null).docs()).isEmpty();
        assertThat(SearchBundle.merge(Collections.singletonList(null)).docs()).isEmpty();
    }

    @Test
    void mergeSkipsNullDocs() {
        SearchBundle.Doc doc = new SearchBundle.Doc("doc-1", "Title", "Snippet", "https://example.test", "");
        SearchBundle merged = SearchBundle.merge(List.of(
                new SearchBundle("web", List.of(doc)),
                new SearchBundle("vector", Collections.singletonList(null))));

        assertThat(merged.docs()).containsExactly(doc);
    }
}
