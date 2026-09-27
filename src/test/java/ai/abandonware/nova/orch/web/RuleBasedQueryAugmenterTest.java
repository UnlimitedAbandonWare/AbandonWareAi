package ai.abandonware.nova.orch.web;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedQueryAugmenterTest {

    @Test
    void namedOfficialProductsAddScopedRescueQueriesForOpenAiAndSupabase() {
        RuleBasedQueryAugmenter augmenter = new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties());

        RuleBasedQueryAugmenter.Augment augment = augmenter.augment(
                "OpenAI Responses API web_search and Supabase MCP read_only project_ref official evidence");

        assertTrue(augment.queries().stream().anyMatch(q -> q.startsWith("site:developers.openai.com ")),
                "OpenAI official evidence probes should include a scoped developer-docs rescue query");
        assertTrue(augment.queries().stream().anyMatch(q -> q.startsWith("site:supabase.com ")),
                "Supabase official evidence probes should include a scoped Supabase-docs rescue query");
    }

    @Test
    void namedOfficialScopedRescueQueriesPrecedeGenericConfiguredTemplates() {
        RuleBasedQueryAugmenter augmenter = new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties());

        RuleBasedQueryAugmenter.Augment augment = augmenter.augment(
                "site:developers.openai.com OR site:supabase.com OpenAI Responses API and Supabase MCP official evidence");

        assertTrue(augment.queries().get(1).startsWith(
                        "site:developers.openai.com OpenAI Responses API web_search file_search computer_use official documentation"),
                String.valueOf(augment.queries()));
        assertTrue(augment.queries().get(2).startsWith(
                        "site:supabase.com Supabase MCP read_only project_ref official documentation"),
                String.valueOf(augment.queries()));
    }

    @Test
    void namedOfficialScopedRescueQueriesDoNotCarryFinanceNegativeTerms() {
        RuleBasedQueryAugmenter augmenter = new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties());

        RuleBasedQueryAugmenter.Augment augment = augmenter.augment(
                "OpenAI Responses API web_search and Supabase MCP read_only project_ref official evidence");

        String openAi = augment.queries().stream()
                .filter(q -> q.startsWith("site:developers.openai.com "))
                .findFirst()
                .orElseThrow();
        String supabase = augment.queries().stream()
                .filter(q -> q.startsWith("site:supabase.com "))
                .findFirst()
                .orElseThrow();

        assertFalse(openAi.contains("-대출"), openAi);
        assertFalse(openAi.contains("-금리"), openAi);
        assertFalse(supabase.contains("-대출"), supabase);
        assertFalse(supabase.contains("-금리"), supabase);
    }
    @Test
    void officialChangelogProbeAddsOpenAiDotComFreshnessRescueQuery() {
        RuleBasedQueryAugmenter augmenter = new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties());

        RuleBasedQueryAugmenter.Augment augment = augmenter.augment(
                "RAG web-search verification: answer only from official OpenAI and Supabase docs/changelog evidence");

        assertTrue(augment.queries().stream().anyMatch(q -> q.startsWith("site:openai.com ")),
                "OpenAI changelog/release probes should include the official openai.com freshness host, not only developer docs");
        assertTrue(augment.queries().stream().anyMatch(q -> q.startsWith("site:supabase.com ")),
                "mixed OpenAI/Supabase official probes should keep the Supabase docs rescue lane");
    }

    @Test
    void officialChangelogProbeAddsCanonicalDeveloperChangelogRescueBeforeGenericDeveloperDocs() {
        RuleBasedQueryAugmenter augmenter = new RuleBasedQueryAugmenter(new NovaWebFailSoftProperties());

        RuleBasedQueryAugmenter.Augment augment = augmenter.augment(
                "RAG web-search verification: answer only from official OpenAI docs/changelog evidence");

        int changelogIndex = augment.queries().indexOf(
                "site:developers.openai.com/api/docs/changelog OpenAI API changelog latest release notes official docs");
        int genericDocsIndex = augment.queries().indexOf(
                "site:developers.openai.com OpenAI Responses API web_search file_search computer_use official documentation");
        assertTrue(changelogIndex >= 0, String.valueOf(augment.queries()));
        assertTrue(genericDocsIndex >= 0, String.valueOf(augment.queries()));
        assertTrue(changelogIndex < genericDocsIndex, String.valueOf(augment.queries()));
    }
}
