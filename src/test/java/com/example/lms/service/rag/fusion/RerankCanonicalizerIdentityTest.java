package com.example.lms.service.rag.fusion;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RerankCanonicalizerIdentityTest {
    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    @ParameterizedTest
    @CsvSource({
            "https://fixture.example/A, https://fixture.example/a",
            "https://fixture.example:8443/a, https://fixture.example:9443/a",
            "http://fixture.example/a, https://fixture.example/a",
            "urn:doc:first, urn:doc:second",
            "https://fixture.example/?q=A, https://fixture.example/?q=a",
            "https://fixture.example/?q=a%26b, https://fixture.example/?q=a&b",
            "https://fixture.example/a%2Fb, https://fixture.example/a/b",
            "https://fixture.example/a, https://fixture.example/a/",
            "https://fixture.example/a, https://fixture.example/a?"
    })
    void distinctResourcesSurviveBothCanonicalizationAndWeightedFusion(String firstUrl, String secondUrl) {
        assertNotEquals(RerankCanonicalizer.canonicalKey(firstUrl), RerankCanonicalizer.canonicalKey(secondUrl));
        var fuser = new WeightedReciprocalRankFuser(60, null, "");
        List<Content> fused = fuser.fuse(List.of(List.of(content("first", firstUrl)),
                List.of(content("second", secondUrl))), List.of(1.0, 1.0), 10);
        assertEquals(2, fused.size());
    }

    @Test
    void schemeHostCaseTrackingAndFragmentStillDeduplicate() {
        String firstUrl = "HTTPS://Docs.Example/RAG/?q=Keep&utm_source=news&fbclid=abc#section";
        String secondUrl = "https://docs.example/RAG/?q=Keep";
        assertEquals(RerankCanonicalizer.canonicalKey(firstUrl), RerankCanonicalizer.canonicalKey(secondUrl));
        var fuser = new WeightedReciprocalRankFuser(60, null, "");
        List<Content> fused = fuser.fuse(List.of(List.of(content("first", firstUrl)),
                List.of(content("second", secondUrl))), List.of(1.0, 1.0), 10);
        assertEquals(1, fused.size());
        assertEquals("first", fused.get(0).textSegment().text());
    }

    @Test
    void emptyHttpPathAndRootPathReferToSameResource() {
        assertEquals(RerankCanonicalizer.canonicalKey("https://fixture.example"),
                RerankCanonicalizer.canonicalKey("https://fixture.example/"));
    }

    @Test
    void invalidAndOpaqueIdsRemainNonemptyWithCaseIntact() {
        assertEquals("urn:doc:First", RerankCanonicalizer.canonicalKey("URN:doc:First#section"));
        assertEquals("Not a URL", RerankCanonicalizer.canonicalKey(" Not a URL#section "));
        assertEquals("", RerankCanonicalizer.canonicalKey(null));
    }

    private Content content(String text, String url) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of("url", url))));
    }
}
