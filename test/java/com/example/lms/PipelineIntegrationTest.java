package com.example.lms;

import com.example.lms.service.prompt.EvidencePromptBuilder;
import com.example.lms.service.prompt.model.PromptContext;
import org.junit.jupiter.api.Test;
import java.util.List;


import static org.junit.jupiter.api.Assertions.*;


public class PipelineIntegrationTest {

    @Test
    void retainsNamedEntitiesAndHasEvidenceSection() {
        EvidencePromptBuilder pb = new EvidencePromptBuilder();
        PromptContext ctx = new PromptContext(
                List.of("푸리나와 에스코피에 관련 문서 스니펫", "스커크/아야카 조합에 대한 요약"),
                List.of("추가 벡터 스니펫: 아야카"),
                List.of()
        );
        String prompt = pb.build(ctx);
        assertTrue(prompt.contains("### Evidence (pinned)"));
        assertTrue(prompt.contains("푸리나") && prompt.contains("아야카"));
    }
}