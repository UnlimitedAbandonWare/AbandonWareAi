package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFailSoftRescueQuerySorterTest {

    @Test
    void rescueSortTraceCatchUsesSuppressionBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/WebFailSoftRescueQuerySorter.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"webFailSoftRescueQuerySorter.trace\", ignore);"));
    }

    @Test
    void changelogFreshnessRescuePrecedesGenericDeveloperDocsRescue() {
        List<String> candidates = new ArrayList<>(List.of(
                "site:developers.openai.com OpenAI Responses API web_search file_search computer_use official documentation",
                "site:openai.com OpenAI changelog release notes latest official docs",
                "site:supabase.com Supabase MCP read_only project_ref official documentation"));

        WebFailSoftRescueQuerySorter.sortOfficialDocsRescueQueries(candidates, null, "test.rescueSort");

        assertEquals("site:openai.com OpenAI changelog release notes latest official docs", candidates.get(0));
    }
}
