package com.example.lms.service;

import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.service.rag.EvidenceAnswerComposer;
import com.example.lms.service.rag.RagEvidenceAttributionService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class PublicEvidenceLocatorIdentityTest {
    @Test void distinctPublicIdsRemainDistinctAcrossBothCitationBoundaries() {
        String alpha="https://example.org/profile?id=alpha", beta="https://example.org/profile?id=beta";
        assertEquals(alpha,RagEvidenceAttributionService.sanitizePublicUrl(alpha));
        assertEquals(beta,RagEvidenceAttributionService.sanitizePublicUrl(beta));
        assertNotEquals(reference(alpha),reference(beta));
        assertEquals(alpha,reference(alpha));
        assertEquals(alpha,new RagEvidenceMetadata("W1","WEB","profile",alpha,null,null,null,1,null,null).source());
    }
    @Test void encodedPathsArePreservedOnceAndTrackingIsRemoved() {
        String raw="https://example.org/%EC%86%8C%EA%B0%9C/%252F?id=alpha&utm_source=fixture#fragment";
        String clean="https://example.org/%EC%86%8C%EA%B0%9C/%252F?id=alpha";
        assertEquals(clean,RagEvidenceAttributionService.sanitizePublicUrl(raw));
        assertEquals(clean,reference(raw));
    }
    @Test void ambiguousOrPrivateLocatorsNeverBecomeAnotherPublicDocument() {
        for(String url:List.of("https://user@example.org/profile?id=alpha",
                "https://example.org/profile?id=alpha&token=synthetic",
                "https://example.org/profile?unknown=alpha",
                "https://example.org/profile?id=alpha&id=beta",
                "https://example.org/profile?id=alpha%26beta",
                "http://127.0.0.1/profile?id=alpha","http://office.local/profile?id=alpha",
                "file:///profile?id=alpha","not a URI")) {
            assertNull(RagEvidenceAttributionService.sanitizePublicUrl(url),url);
            assertNull(reference(url),url);
        }
    }
    @Test void promotedLocatorAndActualBodyStayConnectedWithoutTitleOnlyPromotion() {
        String raw="https://example.org/profile?id=alpha&utm_source=fixture";
        String clean="https://example.org/profile?id=alpha";
        var evidence=new RagEvidenceMetadata("W1","WEB","가상대학교 홍가람 교수",clean,null,null,null,1,null,null);
        var doc=Content.from(TextSegment.from("가상대학교 홍가람 교수",Metadata.from(Map.of("url",raw))));
        var excerpt=EvidenceAnswerComposer.supportedIdentityExcerpt("가상대학교 홍가람 교수 소개",List.of(doc),List.of(evidence)).orElseThrow();
        assertTrue(excerpt.content().contains("> 가상대학교 홍가람 교수"));
        assertTrue(excerpt.content().contains("[W1]("+clean+")"));
        assertFalse(excerpt.content().contains("utm_source"));
        var titleOnly=Content.from(TextSegment.from("진료 안내",Metadata.from(Map.of("url",raw,"title","가상대학교 홍가람 교수"))));
        assertTrue(EvidenceAnswerComposer.supportedIdentityExcerpt("가상대학교 홍가람 교수 소개",List.of(titleOnly),List.of(evidence)).isEmpty());
        var other=new RagEvidenceMetadata("W1","WEB","profile","https://example.org/profile?id=beta",null,null,null,1,null,null);
        assertTrue(EvidenceAnswerComposer.supportedIdentityExcerpt("가상대학교 홍가람 교수 소개",List.of(doc),List.of(other)).isEmpty());
    }
    @Test void hashRoutesDoNotCollapseIntoTheSamePublicDocument() {
        String alpha="https://example.org/#/profile/alpha", beta="https://example.org/#!/profile/beta";
        assertNull(RagEvidenceAttributionService.sanitizePublicUrl(alpha));
        assertNull(reference(beta));
        var promoted=new RagEvidenceMetadata("W1","WEB","profile","https://example.org/",null,null,null,1,null,null);
        var doc=Content.from(TextSegment.from("가상대학교 홍가람 교수",Metadata.from(Map.of("url",alpha))));
        assertTrue(EvidenceAnswerComposer.supportedIdentityExcerpt("가상대학교 홍가람 교수 소개",List.of(doc),List.of(promoted)).isEmpty());
    }
    private static String reference(String url) {
        return ReflectionTestUtils.invokeMethod(ChatWorkflow.class,"sanitizeEvidenceReferenceUrl",url);
    }
}
