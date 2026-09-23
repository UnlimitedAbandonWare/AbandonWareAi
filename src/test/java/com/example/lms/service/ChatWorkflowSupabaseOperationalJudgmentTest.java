package com.example.lms.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowSupabaseOperationalJudgmentTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "GitHub MCP operational decision: is repository read-only access allowed?",
            "GitHub MCP operational decision: is project refactoring allowed?",
            "GitHub MCP operational decision: is project reference allowed?",
            "GitHub MCP operational decision: is x_project_ref allowed?",
            "GitHub MCP operational decision: is project_ref_x allowed?",
            "Supabase shallow copy semantics"
    })
    void unrelatedSubstringMatchesDoNotImplySupabaseOperations(String query) {
        assertFalse(ChatWorkflow.isSupabaseOperationalJudgmentRequest(
                        query),
                "unrelated protocol and substring matches must not enter the Supabase-only short circuit");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Supabase defer",
            "Supabase deferred",
            "Supabase allow",
            "Supabase allowed",
            "Supabase disallow",
            "Supabase disallowed",
            "Supabase operational",
            "Supabase judgment",
            "Supabase decision"
    })
    void explicitSupabaseSingleIntentKeepsLocalEvidenceRoute(String query) {
        assertTrue(ChatWorkflow.isSupabaseOperationalJudgmentRequest(
                        query),
                "explicit Supabase operations should keep the local evidence short circuit");
    }

    @Test
    void projectRefAliasesKeepOperationalEvidenceRoute() {
        assertTrue(ChatWorkflow.isSupabaseOperationalJudgmentRequest("project_ref operational"));
        assertTrue(ChatWorkflow.isSupabaseOperationalJudgmentRequest("project ref operational"));
    }

    @Test
    void supabaseBoundaryWithoutOperationalIntentDoesNotShortCircuit() {
        assertFalse(ChatWorkflow.isSupabaseOperationalJudgmentRequest("Supabase pricing overview"));
        assertFalse(ChatWorkflow.isSupabaseOperationalJudgmentRequest(null));
        assertFalse(ChatWorkflow.isSupabaseOperationalJudgmentRequest("   "));
    }
}
