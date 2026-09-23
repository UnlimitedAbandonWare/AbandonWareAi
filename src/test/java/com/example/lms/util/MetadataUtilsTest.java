package com.example.lms.util;

import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetadataUtilsTest {

    @Test
    void toMapCopiesKnownMetadataKeys() {
        Metadata metadata = Metadata.from(Map.of(
                "url", "https://example.test/doc",
                "rank", 3,
                "score", 0.75d));

        Map<String, Object> out = MetadataUtils.toMap(metadata);
        out.put("url", "mutated");

        assertEquals("https://example.test/doc", metadata.toMap().get("url"));
        assertEquals(3, out.get("rank"));
        assertEquals(0.75d, out.get("score"));
    }

    @Test
    void copyMetadataUsesTypedLangChainMetadataMap() {
        Metadata source = Metadata.from(Map.of(
                "source", "WEB",
                "rank", 2,
                "score", 0.88d));
        Metadata target = Metadata.from(Map.of());

        MetadataUtils.copyMetadata(source, target);

        Map<String, Object> copied = target.toMap();
        assertEquals("WEB", copied.get("source"));
        assertEquals(2, copied.get("rank"));
        assertEquals(0.88d, copied.get("score"));
    }

    @Test
    void toMapSupportsRealQueryMetadataSessionIdWithoutReflection() {
        Object queryMetadata = dev.langchain4j.rag.query.Metadata.from(
                dev.langchain4j.data.message.UserMessage.from("hello"),
                "sid-123",
                java.util.List.of());

        Map<String, Object> out = MetadataUtils.toMap(queryMetadata);

        assertEquals("sid-123", out.get(com.example.lms.service.rag.LangChainRAGService.META_SID));
    }
}
