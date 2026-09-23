package com.example.lms.api;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatApiControllerAutoSearchDecisionTest {

    private final SearchDecisionService decisions = new SearchDecisionService();

    @Test
    void autoModeSkipsWebForPlainLocalChatWhenRagIsOff() {
        boolean useWeb = ChatApiController.shouldUseWebForSearchMode(
                "Local smoke check. Answer in one sentence.",
                SearchMode.AUTO,
                true,
                false,
                decisions,
                5);

        assertFalse(useWeb, "AUTO should not turn every plain chat into a web/RAG safety path");
    }

    @Test
    void autoModeStillAllowsQuestionLikeSearchIntent() {
        boolean useWeb = ChatApiController.shouldUseWebForSearchMode(
                "What changed today?",
                SearchMode.AUTO,
                true,
                false,
                decisions,
                5);

        assertTrue(useWeb, "AUTO should still allow likely search intent");
    }

    @Test
    void autoModeAllowsExplicitRecencyIntentWithoutQuestionMark() {
        boolean useWeb = ChatApiController.shouldUseWebForSearchMode(
                "Latest OpenAI news",
                SearchMode.AUTO,
                true,
                false,
                decisions,
                5);

        assertTrue(useWeb, "AUTO should not suppress an explicit latest/news request just because it has no question mark");
    }

    @Test
    void autoModeDoesNotTreatLocalUpdateCommandAsRecencyIntent() {
        for (String query : List.of(
                "Update my profile",
                "Update your profile",
                "Update profile",
                "Could you update my profile",
                "I need to update my account",
                "Profile update",
                "Do it now",
                "Show current settings")) {
            boolean useWeb = ChatApiController.shouldUseWebForSearchMode(
                    query,
                    SearchMode.AUTO,
                    true,
                    false,
                    decisions,
                    5);

            assertFalse(useWeb,
                    "AUTO recency detection must not turn local language into a web request: " + query);
        }
    }

    @Test
    void autoModeCoversWorkflowRecencyVocabularyWithoutQuestionMark() {
        for (String query : List.of(
                "현재 비트코인 가격",
                "OpenAI API update",
                "Java release",
                "current Bitcoin price",
                "today's weather",
                "Microsoft account update policy",
                "Instagram profile update outage",
                "update the profile schema")) {
            assertTrue(ChatApiController.shouldUseWebForSearchMode(
                    query,
                    SearchMode.AUTO,
                    true,
                    false,
                    decisions,
                    5), "query=" + query);
        }
    }

    @Test
    void explicitSearchModesBypassAutoHeuristic() {
        assertTrue(ChatApiController.shouldUseWebForSearchMode(
                "Search lightly.",
                SearchMode.FORCE_LIGHT,
                true,
                false,
                decisions,
                5));
        assertTrue(ChatApiController.shouldUseWebForSearchMode(
                "Search deeply.",
                SearchMode.FORCE_DEEP,
                true,
                false,
                decisions,
                5));
        assertFalse(ChatApiController.shouldUseWebForSearchMode(
                "Do not search.",
                SearchMode.OFF,
                true,
                false,
                decisions,
                5));
    }

    @Test
    void forceLightMarksGuardContextCheapBeforePrefetch() {
        TraceStore.clear();
        GuardContext context = GuardContext.defaultContext();

        ChatApiController.markCheapSearchMode(context, SearchMode.FORCE_LIGHT, "stream.preSearch");

        assertTrue(context.isCheapSearchMode());
        assertEquals(Boolean.TRUE, TraceStore.get("search.mode.lightAuxBypass"));
        assertEquals("force_light", TraceStore.get("search.mode.lightAuxBypass.reason"));
        TraceStore.clear();
    }

    @Test
    void nonLightModeClearsCheapPrefetchMarker() {
        TraceStore.clear();
        GuardContext context = GuardContext.defaultContext();
        context.setCheapSearchMode(true);

        ChatApiController.markCheapSearchMode(context, SearchMode.AUTO, "sync.preSearch");

        assertFalse(context.isCheapSearchMode());
        assertFalse(Boolean.TRUE.equals(TraceStore.get("search.mode.lightAuxBypass")));
        TraceStore.clear();
    }

    @Test
    void searchModeRewriteHintSeedsRequestedTemperatureAndLanePolicy() {
        TraceStore.clear();

        ChatApiController.recordSearchModeRewriteHint(SearchMode.AUTO);

        assertEquals("AUTO", TraceStore.get("chatApi.web.searchMode"));
        assertEquals("balanced", TraceStore.get("web.query.rewrite.requestedTemperatureProfile"));
        assertEquals(0.15d, (Double) TraceStore.get("web.query.rewrite.requestedValidationTemperature"), 1.0e-9);
        assertEquals(0.55d, (Double) TraceStore.get("web.query.rewrite.requestedExplorationTemperature"), 1.0e-9);
        assertEquals(0.35d, (Double) TraceStore.get("web.query.rewrite.requestedExplorationRate"), 1.0e-9);
        assertEquals(1, TraceStore.get("web.query.rewrite.requestedVerificationLaneCount"));
        assertEquals(2, TraceStore.get("web.query.rewrite.requestedExplorationLaneCount"));

        TraceStore.clear();
    }

    @Test
    void finalWebGateStillWinsOverAutoDecision() {
        assertFalse(ChatApiController.shouldUseWebForSearchMode(
                "What changed today?",
                SearchMode.AUTO,
                false,
                false,
                decisions,
                5));
    }

    @Test
    void autoModeDoesNotForceWebJustBecauseRagIsOn() {
        assertFalse(ChatApiController.shouldUseWebForSearchMode(
                "Hello. Describe the current local state naturally in one sentence.",
                SearchMode.AUTO,
                true,
                true,
                decisions,
                5));
    }

    @Test
    void autoModeAllowsExplicitKoreanWebFactCheckIntent() {
        assertTrue(ChatApiController.shouldUseWebForSearchMode(
                "모르는 내용은 웹에서 찾아 사실관계를 교차 검증해줘.",
                SearchMode.AUTO,
                true,
                true,
                decisions,
                5));
    }

    @Test
    void autoModeAllowsExplicitDomainEvidenceProbeWhenWebIsOn() {
        assertTrue(ChatApiController.shouldUseWebForSearchMode(
                "Browser UI RAG web search final probe: confirm openai.com official docs evidence in one line.",
                SearchMode.AUTO,
                true,
                true,
                decisions,
                5));
    }

    @Test
    void domainEvidenceProbePreservesRequestedDomainInProviderQuery() {
        String query = "Browser UI RAG web search final probe: confirm openai.com official source evidence in one line.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:openai.com "),
                "explicit domain evidence probes should preserve the requested domain for provider search");
        assertTrue(providerQuery.contains(query),
                "provider query should keep the user's original intent after the site filter");
    }

    @Test
    void openAiApiDocsEvidenceStartsWithDeveloperDocsSiteFilter() {
        String query = "openai.com official docs GPT-5 API model source summary";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "OpenAI API/model docs probes should start from the official developer docs host");
        assertTrue(providerQuery.contains(query),
                "provider query should keep the user's original intent after the site filter");
    }

    @Test
    void explicitMultiDomainEvidenceProbeKeepsEveryRequestedOfficialHost() {
        String query = "Verify the three model IDs using official sources from "
                + "developers.openai.com, console.groq.com, and ai.google.dev.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith(
                        "site:developers.openai.com OR site:console.groq.com OR site:ai.google.dev "),
                "one provider query must retain every explicitly requested official host");
        assertTrue(providerQuery.contains(query),
                "provider query should keep the user's original multi-domain intent after site filters");
    }

    @Test
    void koreanParticleAfterDomainKeepsEveryRequestedOfficialHost() {
        String query = "console.groq.com\uACFC ai.google.dev \uACF5\uC2DD \uCD9C\uCC98\uB85C \uD655\uC778";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:console.groq.com OR site:ai.google.dev "),
                "a Korean particle after a domain must not hide that host from the provider query");
        assertTrue(providerQuery.contains(query),
                "the provider query should preserve the original Korean evidence intent");
    }

    @Test
    void callbackExampleOutsideOfficialSourceClauseIsNotAddedToSiteFilter() {
        String query = "Verify OpenAI from official developers.openai.com source; "
                + "the callback example is callback.example.com.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the provider query should retain the official source host");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "a callback example outside the official-source clause must not become a search filter");
    }

    @Test
    void sentencePeriodEndsOfficialSourceClauseBeforeCallbackExample() {
        String query = "Use official sources: developers.openai.com. "
                + "The callback example is callback.example.com.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the provider query should retain the official source host before the sentence boundary");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "a callback example in the next sentence must not become a search filter");
    }

    @Test
    void abbreviationDoesNotEndOfficialSourceClauseBeforeDomain() {
        String query = "Use official sources e.g. developers.openai.com. "
                + "The callback example is callback.example.com.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "an abbreviation must not split the official-source clause before its domain");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "a callback example in the next sentence must not become a search filter");
    }

    @Test
    void closingQuoteEndsOfficialSourceClauseBeforeCallbackExample() {
        String query = "Use official sources: \"developers.openai.com.\" "
                + "The callback example is callback.example.com.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the official host inside the quoted sentence must remain searchable");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "a callback example after the closing quote must not become a search filter");
    }

    @Test
    void cjkClosingQuoteEndsOfficialSourceClauseBeforeCallbackExample() {
        String query = "Use official sources: 「developers.openai.com.」 "
                + "The callback example is callback.example.com.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the official host inside CJK quotes must remain searchable");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "a callback example after a CJK closing quote must not become a search filter");
    }

    @Test
    void cjkFullStopEndsOfficialSourceClauseBeforeCallbackExample() {
        String query = "Use official sources: \u300Cdevelopers.openai.com\u3002\u300D "
                + "The callback example is callback.example.com.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the official host before a CJK full stop must remain searchable");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "a callback example after a CJK full stop must not become a search filter");
    }

    @Test
    void ideographicSpaceEndsOfficialSourceClauseBeforeCallbackExample() {
        String query = "Use official sources: \u300Cdevelopers.openai.com\u3002\u300D\u3000"
                + "The callback example is callback.example.com.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the official host before an ideographic separator must remain searchable");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "an ideographic separator must end the official-source clause before callback text");
    }

    @Test
    void cjkSentenceWithoutSpaceEndsOfficialSourceClauseBeforeCallbackExample() {
        String query = "Use official sources: \u300Cdevelopers.openai.com\u3002\u300D"
                + "\uCF5C\uBC31 \uC608\uC2DC\uB294 callback.example.com\uC785\uB2C8\uB2E4\u3002";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the official host before a no-space CJK boundary must remain searchable");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "CJK terminal punctuation must not require whitespace before the next sentence");
    }

    @Test
    void closingParenthesisBeforeCjkTerminalWithoutSpaceEndsOfficialSourceClause() {
        String query = "Use official sources (developers.openai.com)\u3002"
                + "\uCF5C\uBC31 \uC608\uC2DC\uB294 callback.example.com\uC785\uB2C8\uB2E4\u3002";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the official host inside the parenthesized sentence must remain searchable");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "closing punctuation before a no-space CJK terminal must end the official-source clause");
    }

    @Test
    void closingParenthesisEndsOfficialSourceClauseBeforeCallbackExample() {
        String query = "Use official sources (developers.openai.com). "
                + "The callback example is callback.example.com.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com "),
                "the official host inside the parenthesized sentence must remain searchable");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "a callback example after the closing parenthesis must not become a search filter");
    }

    @Test
    void domainBeforeEnglishOfficialSourcePhraseIsScopedWithoutCallbackExample() {
        String query = "Use console.groq.com as the official source; "
                + "callback.example.com is only an example.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:console.groq.com "),
                "an official host before the English official-source phrase must remain searchable");
        assertFalse(providerQuery.contains("site:callback.example.com"),
                "the following callback example must stay outside the official-source filter");
    }

    @Test
    void namedOfficialSourceProbeAddsOpenAiAndSupabaseSiteFilters() {
        String query = "Compare OpenAI Responses API web_search tooling and Supabase MCP read_only project_ref setup using official/external sources only.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com OR site:supabase.com "),
                "named official-source probes should bias provider search toward the relevant official documentation hosts");
        assertTrue(providerQuery.contains(query),
                "provider query should keep the user's original intent after named official site filters");
    }

    @Test
    void koreanNamedSourceDomainProbeAddsOpenAiAndSupabaseSiteFilters() {
        String query = "OpenAI Responses API web_search/file_search/computer_use and Supabase MCP "
                + "read_only/project_ref setup latest changes reflected in answer evidence, "
                + "\uCD9C\uCC98 \uB3C4\uBA54\uC778 2\uAC1C\uC640 \uADFC\uAC70\uB85C \uAC80\uC99D\uD574\uC918.";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith("site:developers.openai.com OR site:supabase.com "),
                "Korean source-domain evidence probes should bias provider search toward named official docs");
        assertTrue(providerQuery.contains(query),
                "provider query should keep the user's original Korean source-domain intent after site filters");
    }

    @Test
    void namedOfficialSourceProbeDropsUnrelatedFallbackWhenOfficialSourcesExist() {
        String query = "Compare OpenAI Responses API web_search tooling and Supabase MCP read_only project_ref setup using official/external sources only.";
        List<String> snippets = List.of(
                "Community URL: https://github.com/onestardao/WFGY/blob/main/ProblemMap/README.md",
                "OpenAI docs URL: https://developers.openai.com/api/docs/guides/tools-web-search",
                "Streamlit docs URL: https://docs.streamlit.io/develop/tutorials/chat-and-llm-apps/build-conversational-apps",
                "Supabase MCP docs URL: https://supabase.com/docs/guides/ai-tools/mcp");

        List<String> prioritized = ChatApiController.prioritizeDomainEvidenceSnippets(query, snippets);

        assertEquals(2, prioritized.size(),
                "strict named official-source probes should not pass unrelated community/tutorial snippets to the answer prompt when official sources exist");
        assertTrue(prioritized.get(0).contains("developers.openai.com"));
        assertTrue(prioritized.get(1).contains("supabase.com"));
    }

    @Test
    void namedOfficialSourceProbeDropsUnrelatedResultsWhenNoOfficialSourceExists() {
        String query = "Compare OpenAI Responses API web_search tooling and Supabase MCP read_only project_ref setup using official/external sources only.";
        List<String> snippets = List.of(
                "Community URL: https://github.com/onestardao/WFGY/blob/main/ProblemMap/README.md",
                "Vendor tutorial URL: https://docs.nvidia.com/vss/2.3.0/content/faq.html");

        List<String> prioritized = ChatApiController.prioritizeDomainEvidenceSnippets(query, snippets);

        assertEquals(List.of(), prioritized,
                "strict named official-source probes should fail closed instead of feeding off-domain snippets to PromptBuilder");
        assertEquals(true, TraceStore.get("chat.search.providerQuery.namedOfficialFiltered"));
        assertEquals(0, TraceStore.get("chat.search.providerQuery.namedOfficialFilteredCount"));
    }

    @Test
    void koreanOfficialChangelogProbeAddsSiteFiltersAndDropsCommunityResults() {
        String query = "RAG web search: Supabase MCP read_only, 2026 OpenAI latest changes, "
                + "internal Dynamic RAG failure handling, "
                + "\uACF5\uC2DD/changelog \uADFC\uAC70 lane, \uCD5C\uC2E0\uC131 lane, "
                + "\uBC18\uB840 lane, \uCD9C\uCC98 \uB610\uB294 evidence_needed";

        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertTrue(providerQuery.startsWith(
                        "site:developers.openai.com OR site:openai.com OR site:supabase.com "),
                "Korean official/changelog probes should pin provider search to named official hosts");

        List<String> snippets = List.of(
                "OpenAI help URL: https://help.openai.com/en/articles/6825453-chatgpt-release-notes",
                "Community URL: https://github.com/tableMinPark/rag-genAI",
                "Tutorial URL: https://medium.com/example/open-webui-rag",
                "Supabase docs URL: https://supabase.com/docs/guides/ai-tools/mcp");

        List<String> prioritized = ChatApiController.prioritizeDomainEvidenceSnippets(query, snippets);

        assertEquals(2, prioritized.size(),
                "official/changelog probes should not feed GitHub or Medium snippets into the answer prompt when official sources exist");
        assertTrue(prioritized.get(0).contains("help.openai.com"));
        assertTrue(prioritized.get(1).contains("supabase.com"));
    }

    @Test
    void namedOfficialSourceProbeRescuesMissingOfficialDomainCoverage() {
        TraceStore.clear();
        String query = "Compare OpenAI Responses API web_search tooling and Supabase MCP read_only project_ref setup using official/external sources only.";
        List<String> current = List.of(
                "OpenAI docs URL: https://developers.openai.com/api/docs/guides/tools-web-search");
        List<String> searchedQueries = new ArrayList<>();

        List<String> completed = ChatApiController.completeNamedOfficialCoverageSnippets(
                query,
                current,
                rescueQuery -> {
                    searchedQueries.add(rescueQuery);
                    return List.of(
                            "Community mirror URL: https://github.com/supabase/supabase",
                            "Supabase MCP docs URL: https://supabase.com/docs/guides/ai-tools/mcp");
                });

        assertEquals(1, searchedQueries.size(),
                "a partial official-source comparison should issue one scoped rescue query for the missing named product");
        String rescueQuery = searchedQueries.get(0).toLowerCase(java.util.Locale.ROOT);
        assertTrue(rescueQuery.startsWith("site:supabase.com "),
                "a partial official-source comparison should issue one scoped rescue query for the missing named product");
        assertTrue(rescueQuery.contains("supabase"), rescueQuery);
        assertTrue(rescueQuery.contains("mcp"), rescueQuery);
        assertFalse(rescueQuery.contains("openai"),
                "missing-domain rescue should not keep other product tokens that starve provider search");
        assertEquals(2, completed.size(),
                "completed official-source snippets should keep existing official evidence and add the rescued missing domain");
        assertTrue(completed.get(0).contains("developers.openai.com"));
        assertTrue(completed.get(1).contains("supabase.com"));
        assertEquals(1, TraceStore.get("chat.search.providerQuery.namedOfficialCoverageRescueAttemptCount"));
        assertEquals(1, TraceStore.get("chat.search.providerQuery.namedOfficialCoverageRescueAddedCount"));
        TraceStore.clear();
    }

    @Test
    void namedOfficialSourceRescueQueryFocusesMissingDomainTerms() {
        String query = "RAG DEEP 랜덤 탐침: OpenAI Responses API와 Supabase Vector 공식 문서의 "
                + "source domains만 비교하고 evidence_needed와 local/external 경계를 분리해줘";
        List<String> current = List.of(
                "OpenAI docs URL: https://developers.openai.com/api/docs/guides/tools-web-search");
        List<String> searchedQueries = new ArrayList<>();

        ChatApiController.completeNamedOfficialCoverageSnippets(
                query,
                current,
                rescueQuery -> {
                    searchedQueries.add(rescueQuery);
                    return List.of(
                            "Supabase Vector docs URL: https://supabase.com/docs/guides/ai/vector-columns");
                });

        assertEquals(1, searchedQueries.size(),
                "a mixed official-doc comparison should perform one scoped rescue for the missing Supabase domain");
        String rescueQuery = searchedQueries.get(0).toLowerCase(java.util.Locale.ROOT);
        assertTrue(rescueQuery.startsWith("site:supabase.com "),
                "rescue should stay pinned to the missing official domain");
        assertTrue(rescueQuery.contains("supabase"), rescueQuery);
        assertTrue(rescueQuery.contains("vector"), rescueQuery);
        assertFalse(rescueQuery.contains("openai"),
                "missing-domain rescue should not keep other product tokens that starve provider search");
        assertFalse(rescueQuery.contains("responses api"),
                "missing-domain rescue should not keep other product-specific terms");
    }

    @Test
    void officialChangelogCoverageRescueQueriesStripAnswerInstructionNoise() {
        String query = "RAG web-search verification: answer only from official OpenAI and Supabase "
                + "docs/changelog evidence; if official evidence is missing say evidence_needed.";
        List<String> searchedQueries = new ArrayList<>();

        ChatApiController.completeNamedOfficialCoverageSnippets(
                query,
                List.of(),
                rescueQuery -> {
                    searchedQueries.add(rescueQuery);
                    return List.of();
                });

        assertEquals(3, searchedQueries.size(),
                "the exact soak probe should try each named official domain independently");
        assertTrue(searchedQueries.stream().anyMatch(q -> q.startsWith("site:developers.openai.com ")),
                String.valueOf(searchedQueries));
        assertTrue(searchedQueries.stream().anyMatch(q -> q.startsWith("site:openai.com ")),
                String.valueOf(searchedQueries));
        assertTrue(searchedQueries.stream().anyMatch(q -> q.startsWith("site:supabase.com ")),
                String.valueOf(searchedQueries));
        for (String rescueQuery : searchedQueries) {
            String lower = rescueQuery.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("answer only"), rescueQuery);
            assertFalse(lower.contains("evidence_needed"), rescueQuery);
            assertFalse(lower.contains("is missing"), rescueQuery);
        }
    }

    @Test
    void domainEvidenceProviderQueryDoesNotDoublePrefixSiteSearch() {
        String query = "Compare site:openai.com Responses API evidence with site:supabase.com MCP evidence.";

        assertTrue(ChatApiController.domainEvidenceSearchQuery(query).startsWith(query),
                "existing site: filters should be left intact");
    }

    @Test
    void plainChatProviderQueryIsUnchanged() {
        String query = "Hello. Describe the current local state naturally in one sentence.";

        assertTrue(ChatApiController.domainEvidenceSearchQuery(query).equals(query),
                "plain local chat should not receive a site filter");
    }

    @Test
    void domainEvidenceSnippetsPrioritizeRequestedDomainWithoutDroppingRemainder() {
        String query = "Browser UI RAG web search final probe: confirm openai.com official docs evidence in one line.";
        List<String> snippets = List.of(
                "NIPA URL: https://www.nipa.kr/comm/getFile",
                "OpenAI Help Center URL: https://help.openai.com/ko-kr",
                "Other URL: https://example.org/reference");

        List<String> prioritized = ChatApiController.prioritizeDomainEvidenceSnippets(query, snippets);

        assertTrue(prioritized.get(0).contains("help.openai.com"),
                "requested domain evidence should be promoted ahead of unrelated sources");
        assertTrue(prioritized.containsAll(snippets),
                "domain prioritization should not drop fallback snippets needed by citation gates");
    }

    @Test
    void domainEvidenceSnippetsDoNotTreatMentionedDomainAsSourceUrl() {
        String query = "openai.com 공식 문서 근거로 GPT-5 API 모델 설명을 출처와 함께 한 줄 요약";
        List<String> snippets = List.of(
                "Community recap mentions help.openai.com URL: https://velog.io/@hyuckjin/ai-tool-updates",
                "OpenAI docs URL: https://developers.openai.com/api/docs/models/gpt-5");

        List<String> prioritized = ChatApiController.prioritizeDomainEvidenceSnippets(query, snippets);

        assertTrue(prioritized.get(0).contains("developers.openai.com"),
                "source URL host, not arbitrary snippet text, should decide requested-domain priority");
    }

    @Test
    void domainEvidenceSiteFilterAllowsRawQueryRetryWhenProviderReturnsNoSnippets() {
        String query = "Browser UI RAG web search final probe: confirm openai.com official docs evidence in one line.";
        String providerQuery = ChatApiController.domainEvidenceSearchQuery(query);

        assertEquals(query, ChatApiController.domainEvidenceFallbackSearchQuery(query, providerQuery, List.of()),
                "a strict site-filter zero-result should retry the original query instead of ending as WEB_MISS");
        assertEquals(null, ChatApiController.domainEvidenceFallbackSearchQuery(query, providerQuery,
                        List.of("OpenAI Help Center URL: https://help.openai.com/ko-kr")),
                "non-empty site-filter results should not spend another provider call");
        assertEquals(null, ChatApiController.domainEvidenceFallbackSearchQuery(query, query, List.of()),
                "plain/raw provider queries should not recursively retry themselves");
    }

    @Test
    void domainEvidenceFallbackRejectsCommunityOnlyRawResults() {
        String query = "openai.com 공식 문서 근거로 GPT-5 API 모델 설명을 출처와 함께 한 줄 요약";
        List<String> communityOnly = List.of(
                "Velog summary mentions help.openai.com but URL: https://velog.io/@hyuckjin/ai-tool-updates",
                "Wiki source URL: https://namu.wiki/w/ChatGPT",
                "Blog source URL: https://tistory.com/example/openai-api-key");

        List<String> accepted = ChatApiController.acceptDomainEvidenceFallbackSnippets(
                query, communityOnly, List.of());

        assertEquals(List.of(), accepted,
                "raw fallback for explicit domain evidence should not promote community-only results as source proof");
    }

    @Test
    void domainEvidenceFallbackAcceptsRequestedDomainRawResults() {
        String query = "openai.com 공식 문서 근거로 GPT-5 API 모델 설명을 출처와 함께 한 줄 요약";
        List<String> rawFallback = List.of(
                "Community URL: https://velog.io/@hyuckjin/ai-tool-updates",
                "OpenAI docs URL: https://developers.openai.com/api/docs/models/gpt-5");

        List<String> accepted = ChatApiController.acceptDomainEvidenceFallbackSnippets(
                query, rawFallback, List.of());

        assertTrue(accepted.get(0).contains("developers.openai.com"),
                "requested-domain fallback snippets should be accepted and promoted ahead of community sources");
        assertTrue(accepted.containsAll(rawFallback),
                "accepted fallback snippets should preserve remainder evidence for downstream citation gates");
    }

    @Test
    void webPrefetchTraceRecordsCountsWithoutRawProviderQuery() {
        TraceStore.clear();
        String providerQuery = "site:developers.openai.com openai.com official docs GPT-5 API model source summary";

        ChatApiController.recordWebPrefetchTrace(
                "stream",
                true,
                true,
                true,
                SearchMode.FORCE_DEEP,
                5,
                providerQuery,
                List.of("OpenAI docs URL: https://developers.openai.com/api/docs/models/gpt-5"));

        Map<String, Object> trace = TraceStore.getAll();
        assertEquals(true, trace.get("chatApi.web.prefetch.stream.requestUseWeb"));
        assertEquals(true, trace.get("chatApi.web.prefetch.stream.resolvedUseWeb"));
        assertEquals(true, trace.get("chatApi.web.prefetch.stream.resolvedUseRag"));
        assertEquals("FORCE_DEEP", trace.get("chatApi.web.prefetch.stream.searchMode"));
        assertEquals(5, trace.get("chatApi.web.prefetch.stream.topK"));
        assertEquals(1, trace.get("chatApi.web.prefetch.stream.snippetCount"));
        assertEquals(providerQuery.length(), trace.get("chatApi.web.prefetch.stream.providerQueryLength"));
        assertEquals("developers.openai.com", trace.get("chatApi.web.prefetch.stream.providerQuerySiteDomain"));
        assertTrue(trace.containsKey("chatApi.web.prefetch.stream.providerQueryHash"));
        assertFalse(trace.containsValue(providerQuery),
                "pre-LLM diagnostics must not expose raw provider queries");
        TraceStore.clear();
    }

    @Test
    void controllerPassesResolvedSearchDecisionIntoWorkflowRequest() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("__directUiModeStatusAnswer ? __workflowUseWeb : allowWeb"),
                "streaming path should pass the resolved AUTO search decision into ChatWorkflow unless preserving direct UI status");
        assertTrue(source.contains("__directUiModeStatusAnswer ? __workflowUseWeb : performSearch"),
                "sync path should pass the resolved AUTO search decision into ChatWorkflow unless preserving direct UI status");
    }

    @Test
    void controllerPreservesUiModeStatusSearchControlInWorkflowRequest() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);

        int streamPreserve = source.indexOf(
                "final boolean __workflowUseWebForCall = __directUiModeStatusAnswer ? __workflowUseWeb : allowWeb;");
        int streamCall = source.indexOf(".useWebSearch(__workflowUseWebForCall)", streamPreserve);
        int syncPreserve = source.indexOf(
                "final boolean __workflowUseWebForCall = __directUiModeStatusAnswer ? __workflowUseWeb : performSearch;",
                Math.max(streamCall, 0));
        int syncCall = source.indexOf(".useWebSearch(__workflowUseWebForCall)", syncPreserve);

        assertTrue(streamPreserve > 0,
                "streaming direct UI status answers should preserve the selected Search control in the workflow DTO");
        assertTrue(streamCall > streamPreserve,
                "streaming ChatWorkflow call should use the preserved direct UI Search control value");
        assertTrue(syncPreserve > streamCall,
                "sync direct UI status answers should preserve the selected Search control in the workflow DTO");
        assertTrue(syncCall > syncPreserve,
                "sync ChatWorkflow call should use the preserved direct UI Search control value");
    }
}
