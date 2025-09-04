package com.example.lms;

import com.example.lms.service.prompt.EvidencePromptBuilder;
import com.example.lms.prompt.PromptContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class PipelineIntegrationTest {

    @Test
    void retainsNamedEntitiesAndHasEvidenceSection() {
        EvidencePromptBuilder pb = new EvidencePromptBuilder();
        // Build a PromptContext using the builder and assign web and rag lists.  The generics
        // on PromptContext#web and #rag expect Content instances but raw lists of Strings can be
        // passed via an unchecked cast for testing purposes.
        @SuppressWarnings({"unchecked", "rawtypes"})
        PromptContext ctx = PromptContext.builder()
                .web((List) List.of("푸리나와 에스코피에 관련 문서 스니펫", "스커크/아야카 조합에 대한 요약"))
                .rag((List) List.of("추가 벡터 스니펫: 아야카"))
                .build();
        String prompt = pb.build(ctx);
        assertTrue(prompt.contains("### Evidence (pinned)"));
        assertTrue(prompt.contains("푸리나") && prompt.contains("아야카"));
    }
}
