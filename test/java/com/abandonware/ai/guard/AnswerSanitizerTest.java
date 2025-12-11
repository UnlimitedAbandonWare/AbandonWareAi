package com.abandonware.ai.guard;

import com.abandonware.ai.service.rag.auth.DomainProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DummyLoader extends DomainProfileLoader {
    public DummyLoader() { super(null); }
    @Override public Set<String> load(String profileKey) { return Set.of("gov", "news", "edu"); }
}

public class AnswerSanitizerTest {

    @Test
    void pii_mask_and_banner_and_citations() {
        AnswerSanitizer s = new AnswerSanitizer(new DummyLoader());
        String out = s.sanitize("Email a@b.com Phone 010-1234-5678", List.of("https://news.site/x","https://gov.site/y","https://edu.site/z"), true);
        assertTrue(out.contains("【주의"));
        assertFalse(out.contains("a@b.com"));
        assertTrue(out.contains("010-****-****"));
    }
}