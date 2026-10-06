package com.example.lms.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.rag.content.Content;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoEvidenceChatFallbackTest {

    @Test
    void explicitNoAdditionalSearchControlsRetrievalOnly() {
        assertTrue(NoEvidenceChatFallback.isExplicitNoAdditionalSearch(
                "추가 검색 없이 앞에서 확인된 출처만으로 확인 범위를 구분해줘."));
        assertTrue(NoEvidenceChatFallback.isExplicitNoAdditionalSearch(
                "외부 검색은 하지 말고 그 문서 기준으로 설명해줘."));
        assertFalse(NoEvidenceChatFallback.isExplicitNoAdditionalSearch(null));
        assertFalse(NoEvidenceChatFallback.isExplicitNoAdditionalSearch(
                "추가 검색 없이는 답하지 마. 최신 출처를 검색해줘."));
        assertFalse(NoEvidenceChatFallback.isExplicitNoAdditionalSearch("추가 검색으로 현재 상태를 확인해줘."));
        assertFalse(NoEvidenceChatFallback.isExplicitNoAdditionalSearch(
                "추가 검색 없이 답한다는 방식의 문제를 설명해줘. 최신 출처도 검색해줘."));
    }


    @Test
    void clarificationFirstPriorComparisonExcludesNewAndAdditionalTasks() {
        String query = "앞에서 확인한 원신 자료 두 개를 비교해줘. 어느 두 자료인지 또는 비교 기준이 불명확하면 먼저 확인 질문을 해줘.";
        assertTrue(NoEvidenceChatFallback.isClarificationFirstPriorComparison(query));
        assertTrue(NoEvidenceChatFallback.isClarificationFirstPriorComparison(query.replace("원신", "Spring Security")));
        assertFalse(NoEvidenceChatFallback.isClarificationFirstPriorComparison(query + " 최신 공지도 찾아줘."));
        assertFalse(NoEvidenceChatFallback.isClarificationFirstPriorComparison("새로운 원신 자료 두 개를 찾아서 비교해줘."));
        assertFalse(NoEvidenceChatFallback.isClarificationFirstPriorComparison("앞에서 확인한 자료 A와 자료 B를 비교해줘."));
        assertFalse(NoEvidenceChatFallback.isClarificationFirstPriorComparison(null));
    }

    @Test
    void generalConceptOptOutDoesNotAuthorizeMedicationUse() {
        assertFalse(NoEvidenceChatFallback.isExplicitGeneralConceptOnly(
                "아스피린과 와파린을 함께 복용해도 되는지를 일반적인 개념으로 3문장만 설명해줘. 외부 검색이 필요한 주제는 아니야."));
    }

    @Test
    void explicitGeneralConceptIntentExcludesFactualAndAdditionalTasks() {
        String concept = "RAG에서 키워드 검색과 벡터 검색의 차이를 일반적인 개념으로 3문장만 설명해줘. 외부 검색이 필요한 주제는 아니야.";
        assertTrue(NoEvidenceChatFallback.isExplicitGeneralConceptOnly(concept));
        for (String prefix : java.util.List.of("현재 ", "공식 출처의 ", "의료 진단의 ", "교수 소속의 ",
                "CEO의 ", "웹에서 찾아 ")) {
            assertFalse(NoEvidenceChatFallback.isExplicitGeneralConceptOnly(prefix + concept), prefix);
        }
        assertFalse(NoEvidenceChatFallback.isExplicitGeneralConceptOnly(concept + " 최신 버전도 확인해줘."));
        assertFalse(NoEvidenceChatFallback.isExplicitGeneralConceptOnly(
                "RAG에서 키워드 검색과 벡터 검색의 차이를 설명해줘."));
    }

    @Test
    void sessionAssignmentsAndCorrectionsNeedConversationContextOnly() throws Exception {
        var classifier = NoEvidenceChatFallback.class.getDeclaredMethod("isSessionMemoryOnly", String.class);
        classifier.setAccessible(true);
        assertTrue((boolean) classifier.invoke(null,
                "이 대화에서만 시험 프로젝트 이름 해솔-42, 색상 청록, "
                        + "비교 기준 공식 자료 우선·확인 가능한 갱신일을 기억해줘. "
                        + "계정의 장기 기억에 저장할 필요는 없어."));
        assertTrue((boolean) classifier.invoke(null,
                "방금 정한 프로젝트 이름·색상·비교 기준을 다시 말해줘. "
                        + "그리고 이 대화의 색상은 남색으로 정정해줘."));
    }

    @Test
    void sessionRecallWithSearchOptOutNeedsConversationContextOnly() throws Exception {
        var classifier = NoEvidenceChatFallback.class.getDeclaredMethod("isSessionMemoryOnly", String.class);
        classifier.setAccessible(true);
        assertTrue((boolean) classifier.invoke(null,
                "이 대화의 시험 프로젝트 이름, 마지막으로 정정된 색상, "
                        + "처음 정한 비교 기준을 정확히 다시 말해줘. 외부 검색은 필요 없어."));
    }

    @Test
    void searchOptOutDoesNotConvertAdditionalTasksIntoSessionMemory() throws Exception {
        var classifier = NoEvidenceChatFallback.class.getDeclaredMethod("isSessionMemoryOnly", String.class);
        classifier.setAccessible(true);
        for (String query : java.util.List.of(
                "이 대화에서 외부 검색은 필요 없어.",
                "이 대화에서 색상 청록을 기억해줘. 외부 검색은 필요 없어. 아스피린과 와파린을 같이 먹어도 되는지 알려줘.",
                "이 대화에서 프로젝트 이름을 다시 말해줘. 외부 검색은 필요 없어. 최신 원신 공식 공지를 확인해줘.")) {
            assertFalse((boolean) classifier.invoke(null, query), query);
        }
    }

    @Test
    void mixedSessionAndExternalEvidenceRequestsKeepRetrieval() throws Exception {
        var classifier = NoEvidenceChatFallback.class.getDeclaredMethod("isSessionMemoryOnly", String.class);
        classifier.setAccessible(true);
        for (String query : List.of(
                "이 대화에서 최신 원신 공식 공지를 찾아서 기억해줘.",
                "이 대화에서 프로젝트 이름 해솔-42를 기억해줘. 그리고 아스피린과 와파린을 함께 복용해도 되는지 알려줘.",
                "이 대화에서 프로젝트 이름 해솔-42를 기억해줘 그리고 약 복용 여부를 알려줘.",
                "이 대화의 색상을 정정해줘. 그리고 공식 원문을 확인해줘.",
                "추가 검색 없이 앞에서 확인된 원신 출처로 오늘 상태를 말해줘.",
                "공식 자료 우선·확인 가능한 갱신일을 알려줘.")) {
            assertFalse((boolean) classifier.invoke(null, query), query);
        }
    }

    @Test
    void discoveringAnExternalValueBeforeRememberingKeepsVerification() throws Exception {
        var classifier = NoEvidenceChatFallback.class.getDeclaredMethod("isSessionMemoryOnly", String.class);
        classifier.setAccessible(true);
        assertFalse((boolean) classifier.invoke(null,
                "이 대화에서 OpenAI의 대표이사 이름을 알아내서 기억해줘."));
    }

    @Test
    void sessionMemoryRouteUsesExistingDirectPromptAndNotRequiredVerification() throws Exception {
        String workflow = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/com/example/lms/service/ChatWorkflow.java"));
        assertTrue(workflow.contains("final boolean sessionMemoryDirectMode ="));
        assertTrue(workflow.contains("final boolean directRetrievalOffMode = sessionMemoryDirectMode"));
        assertTrue(workflow.contains("final boolean casualGreetingNoEvidenceIntent = sessionMemoryDirectMode"));
        assertTrue(workflow.contains("if (casualGreetingNoEvidenceIntent) {\n            useWeb = false;\n            useRag = false;"));
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void greetingFallbackAnswersInsteadOfAskingForEvidence() {
        String answer = NoEvidenceChatFallback.compose(
                "\uC548\uB155. \uD55C \uBB38\uC7A5\uC73C\uB85C \uC778\uC0AC\uD574\uC918.");

        assertTrue(answer.contains("\uC548\uB155\uD558\uC138\uC694"));
        assertTrue(answer.contains("\uB85C\uCEEC \uC548\uC804 \uC751\uB2F5"));
        assertTrue(answer.contains("\uBA3C\uC800 \uC548\uB0B4\uB4DC\uB9BD\uB2C8\uB2E4"));
        assertFalse(looksMojibaked(answer));
        assertFalse(answer.toLowerCase(Locale.ROOT).contains("evidence"));
        assertFalse(answer.contains("\uADFC\uAC70\uB97C \uB2E4\uC2DC"));
    }

    @Test
    void genericFallbackIsTransparentAndNonBlank() {
        String answer = NoEvidenceChatFallback.compose(
                "\uC624\uB298 \uB300\uD654 \uAC00\uB2A5\uD55C \uC0C1\uD0DC\uC778\uC9C0 \uC54C\uB824\uC918");

        assertTrue(answer.contains("\uB85C\uCEEC \uC548\uC804 \uC751\uB2F5"));
        assertTrue(answer.contains("\uC9C8\uBB38\uC740 \uC811\uC218\uD588\uC2B5\uB2C8\uB2E4"));
        assertTrue(answer.contains("\uCD94\uAC00 \uADFC\uAC70"));
        assertFalse(looksMojibaked(answer));
        assertTrue(answer.length() > 40);
        assertFalse(answer.toLowerCase(Locale.ROOT).contains("evidence-only"));
    }

    @Test
    void officialEvidenceFallbackReportsEvidenceNeededWhenNoEvidence() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "RAG web-search verification: answer only from official OpenAI and Supabase "
                        + "docs/changelog evidence; if official evidence is missing say evidence_needed.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "generic evidence fallback");

        assertTrue(result.content().contains("evidence_needed"), result.content());
        assertFalse(result.content().contains("\uB85C\uCEEC \uC548\uC804 \uC751\uB2F5"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"));
    }

    @Test
    void officialEvidenceFallbackRejectsOffDomainEvidence() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "RAG web-search verification: answer only from official OpenAI and Supabase "
                        + "docs/changelog evidence; if official evidence is missing say evidence_needed.",
                "model-x",
                true,
                List.of(Content.from("GitHub mirror notes https://github.com/example/not-official")),
                List.of(),
                List.of(),
                "GitHub mirror notes https://github.com/example/not-official");

        assertTrue(result.content().contains("evidence_needed"), result.content());
        assertFalse(result.content().contains("github.com"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"));
    }

    @Test
    void domainTermsStartingWithHiAreNotTreatedAsGreetings() {
        String answer = NoEvidenceChatFallback.compose(
                "\uD558\uC774\uD37C\uB124\uD06C \uBD88\uD655\uC815\uC131 \uC6D0\uB9AC\uAC00 \uBB50\uB0D0?");

        assertTrue(answer.contains("\uC9C8\uBB38\uC740 \uC811\uC218\uD588\uC2B5\uB2C8\uB2E4"));
        assertFalse(answer.contains("\uBB34\uC5C7\uC744 \uB3C4\uC640\uB4DC\uB9B4\uAE4C\uC694"));
    }

    @Test
    void noEvidencePredicateRequiresAllRetrievalListsToBeEmpty() {
        assertTrue(NoEvidenceChatFallback.hasNoEvidence(null, List.of()));
        assertFalse(NoEvidenceChatFallback.hasNoEvidence(List.of("doc"), List.of()));
    }

    @Test
    void agentVisibleDebugDocsAreSupportingOnlyForFallbackSelection() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "\uD55C \uBB38\uC7A5\uC73C\uB85C OK\uB77C\uACE0 \uB2F5\uD574\uC918",
                "model-x",
                false,
                List.of(),
                List.of(),
                List.of(Document.from("AGENT_VISIBLE_DEBUG_HEARTBEAT\nbrowser.status=OK")),
                "debug evidence answer");

        assertTrue(result.content().contains("\uB85C\uCEEC \uC548\uC804 \uC751\uB2F5"));
        assertFalse(result.content().contains("debug evidence answer"));
        assertTrue(result.modelUsed().contains(":fallback:local-lite"));
        assertFalse(result.modelUsed().contains(":fallback:evidence"));
    }

    @Test
    void userLocalDocsStillCountAsEvidenceForFallbackSelection() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "uploaded note status",
                "model-x",
                false,
                List.of(),
                List.of(),
                List.of(Document.from("uploaded user note evidence")),
                "debug evidence answer");

        assertTrue(result.content().contains("debug evidence answer"));
        assertTrue(result.modelUsed().contains(":fallback:evidence"));
    }

    @Test
    void evidenceFallbackPreservesPromotedCitationMetadata() {
        RagEvidenceMetadata citation = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "Example",
                "https://example.test/page",
                null,
                null,
                null,
                1,
                0.91,
                "test");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "question",
                "model-x",
                true,
                List.of("web-doc"),
                List.of(),
                List.of(),
                "evidence fallback answer",
                List.of(citation));

        assertTrue(result.content().contains("evidence fallback answer"));
        assertTrue(result.modelUsed().contains(":fallback:evidence"));
        assertEquals(1, result.evidenceMetadata().size());
        assertEquals("[W1]", result.evidenceMetadata().get(0).marker());
    }

    @Test
    void officialEvidenceFallbackAcceptsMatchingCitationMetadataWhenModelFails() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "OpenAI Responses API",
                "https://developers.openai.com/api/docs/guides/tools-web-search",
                null,
                null,
                null,
                1,
                0.91,
                "web");
        RagEvidenceMetadata supabase = new RagEvidenceMetadata(
                "[W2]",
                "web",
                "Supabase MCP",
                "https://supabase.com/docs/guides/ai-tools/mcp",
                null,
                null,
                null,
                2,
                0.9,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "RAG web-search verification: answer only from official OpenAI and Supabase "
                        + "docs/changelog evidence; if official evidence is missing say evidence_needed.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "official evidence fallback with [W1] and [W2]",
                List.of(openAi, supabase));

        assertFalse(result.content().contains("evidence_needed"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"));
        assertEquals(2, result.evidenceMetadata().size());
    }

    @Test
    void hostileSuffixMetadataDoesNotSatisfyNamedOfficialEvidence() {
        RagEvidenceMetadata hostileOpenAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "Untrusted OpenAI mirror",
                "https://developers.openai.com.evil/path",
                null,
                null,
                null,
                1,
                0.91,
                "web");
        RagEvidenceMetadata hostileSupabase = new RagEvidenceMetadata(
                "[W2]",
                "web",
                "Untrusted Supabase mirror",
                "https://supabase.com.evil/path",
                null,
                null,
                null,
                2,
                0.9,
                "web");

        ChatResult openAi = NoEvidenceChatFallback.orEvidenceFallback(
                "OpenAI official source evidence for latest API changes",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "hostile OpenAI fallback",
                List.of(hostileOpenAi));
        ChatResult supabase = NoEvidenceChatFallback.orEvidenceFallback(
                "Supabase official source evidence for latest MCP changes",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "hostile Supabase fallback",
                List.of(hostileSupabase));

        assertTrue(openAi.content().contains("evidence_needed"), openAi.content());
        assertFalse(openAi.content().contains("hostile OpenAI fallback"), openAi.content());
        assertTrue(openAi.modelUsed().contains(":fallback:local-lite"), openAi.modelUsed());
        assertTrue(supabase.content().contains("evidence_needed"), supabase.content());
        assertFalse(supabase.content().contains("hostile Supabase fallback"), supabase.content());
        assertTrue(supabase.modelUsed().contains(":fallback:local-lite"), supabase.modelUsed());
    }

    @Test
    void unofficialSourceCannotBorrowOfficialProvenanceFromEvidenceBody() {
        RagEvidenceMetadata untrusted = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "Untrusted mirror",
                "https://untrusted.example/article",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "OpenAI official source evidence for latest API changes",
                "model-x",
                true,
                List.of(Content.from("For reference, developers.openai.com has documentation.")),
                List.of(),
                List.of(),
                "untrusted mirror fallback",
                List.of(untrusted));

        assertTrue(result.content().contains("evidence_needed"), result.content());
        assertFalse(result.content().contains("untrusted mirror fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
    }

    @Test
    void partialMultiProviderOfficialEvidenceFailsClosed() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "GPT-5.5",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null,
                null,
                null,
                1,
                0.91,
                "web");
        RagEvidenceMetadata google = new RagEvidenceMetadata(
                "[W2]",
                "web",
                "Gemini 2.5 Pro",
                "https://ai.google.dev/gemini-api/docs/models/gemini-2.5-pro",
                null,
                null,
                null,
                2,
                0.9,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Verify gpt-5.5, openai/gpt-oss-120b, and gemini-2.5-pro from official "
                        + "developers.openai.com, console.groq.com, and ai.google.dev sources; "
                        + "if any official evidence is missing say evidence_needed.",
                "model-x",
                true,
                List.of(Content.from(
                        "Untrusted mirror text mentions https://console.groq.com but is not the requested source.")),
                List.of(),
                List.of(),
                "partial official evidence fallback with [W1] and [W2]",
                List.of(openAi, google));

        assertTrue(result.content().contains("evidence_needed"), result.content());
        assertTrue(result.content().contains("- console.groq.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("- developers.openai.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("- ai.google.dev: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("partial official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
        assertEquals("official_evidence_missing",
                TraceStore.get("chat.llmFallback.evidenceNeededReason"));
    }

    @Test
    void explicitGroqAndGoogleOfficialSourcesActivateMissingEvidenceFallback() {
        RagEvidenceMetadata groq = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "Groq model docs",
                "https://console.groq.com/docs/model/openai/gpt-oss-120b",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Answer only from official console.groq.com and ai.google.dev sources; "
                        + "report every missing official source.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "partial official evidence fallback",
                List.of(groq));

        assertTrue(result.content().contains("- ai.google.dev: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("- console.groq.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("partial official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
    }

    @Test
    void koreanOfficialSourceContextKeepsEveryExplicitDomainInThePolicy() {
        RagEvidenceMetadata google = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "Google model docs",
                "https://ai.google.dev/gemini-api/docs/models/gemini",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "console.groq.com\uACFC ai.google.dev \uACF5\uC2DD \uCD9C\uCC98\uB85C\uB9CC \uD655\uC778\uD574\uC918.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "partial official evidence fallback",
                List.of(google));

        assertTrue(result.content().contains("- console.groq.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("- ai.google.dev: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("partial official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
    }

    @Test
    void unrelatedExampleUrlIsNotRequiredAsOfficialEvidenceHost() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Verify OpenAI from official developers.openai.com source; "
                        + "the callback example is callback.example.com.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "official evidence fallback",
                List.of(openAi));

        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void sentencePeriodEndsOfficialSourceClauseBeforeCallbackExample() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources: developers.openai.com. "
                        + "The callback example is callback.example.com.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "official evidence fallback",
                List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void abbreviationDoesNotEndOfficialSourceClauseBeforeDomain() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources e.g. developers.openai.com. "
                        + "The callback example is callback.example.com.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "official evidence fallback",
                List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void closingQuoteEndsOfficialSourceClauseBeforeCallbackExample() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources: \"developers.openai.com.\" "
                        + "The callback example is callback.example.com.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "official evidence fallback",
                List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void cjkClosingQuoteEndsOfficialSourceClauseBeforeCallbackExample() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources: 「developers.openai.com.」 "
                        + "The callback example is callback.example.com.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "official evidence fallback",
                List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void cjkFullStopEndsOfficialSourceClauseBeforeCallbackExample() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]", "web", "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null, null, null, 1, 0.91, "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources: \u300Cdevelopers.openai.com\u3002\u300D "
                        + "The callback example is callback.example.com.",
                "model-x", true, List.of(), List.of(), List.of(),
                "official evidence fallback", List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void ideographicSpaceEndsOfficialSourceClauseBeforeCallbackExample() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]", "web", "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null, null, null, 1, 0.91, "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources: \u300Cdevelopers.openai.com\u3002\u300D\u3000"
                        + "The callback example is callback.example.com.",
                "model-x", true, List.of(), List.of(), List.of(),
                "official evidence fallback", List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void cjkSentenceWithoutSpaceEndsOfficialSourceClauseBeforeCallbackExample() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]", "web", "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null, null, null, 1, 0.91, "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources: \u300Cdevelopers.openai.com\u3002\u300D"
                        + "\uCF5C\uBC31 \uC608\uC2DC\uB294 callback.example.com\uC785\uB2C8\uB2E4\u3002",
                "model-x", true, List.of(), List.of(), List.of(),
                "official evidence fallback", List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void closingParenthesisBeforeCjkTerminalWithoutSpaceEndsOfficialSourceClause() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]", "web", "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null, null, null, 1, 0.91, "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources (developers.openai.com)\u3002"
                        + "\uCF5C\uBC31 \uC608\uC2DC\uB294 callback.example.com\uC785\uB2C8\uB2E4\u3002",
                "model-x", true, List.of(), List.of(), List.of(),
                "official evidence fallback", List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void closingParenthesisEndsOfficialSourceClauseBeforeCallbackExample() {
        RagEvidenceMetadata openAi = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "OpenAI model docs",
                "https://developers.openai.com/api/docs/models/gpt-5.5",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources (developers.openai.com). "
                        + "The callback example is callback.example.com.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "official evidence fallback",
                List.of(openAi));

        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("EVIDENCE_NEEDED"), result.content());
        assertTrue(result.content().contains("official evidence fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:evidence"), result.modelUsed());
    }

    @Test
    void abbreviationStillRequiresTheFollowingOfficialDomain() {
        RagEvidenceMetadata callback = new RagEvidenceMetadata(
                "[W1]",
                "web",
                "Callback example",
                "https://callback.example.com/reference",
                null,
                null,
                null,
                1,
                0.91,
                "web");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use official sources e.g. developers.openai.com. "
                        + "The callback example is callback.example.com.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "unverified callback fallback",
                List.of(callback));

        assertTrue(result.content().contains("- developers.openai.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("unverified callback fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
    }

    @Test
    void domainBeforeEnglishOfficialSourcePhraseStillFailsClosed() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "Use console.groq.com as the official source; "
                        + "callback.example.com is only an example.",
                "model-x",
                true,
                List.of(),
                List.of(),
                List.of(),
                "generic callback fallback",
                List.of());

        assertTrue(result.content().contains("- console.groq.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("callback.example.com: EVIDENCE_NEEDED"), result.content());
        assertFalse(result.content().contains("generic callback fallback"), result.content());
        assertTrue(result.modelUsed().contains(":fallback:local-lite"), result.modelUsed());
    }

    @Test
    void blankEvidenceFallbackRecoversWithCompactEvidenceListWhenWebDocsExist() {
        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "\uB300\uD55C\uBBFC\uAD6D \uC218\uB3C4",
                "model-x",
                false,
                List.of(Content.from("\uB300\uD55C\uBBFC\uAD6D\uC758 \uC218\uB3C4\uB294 \uC11C\uC6B8\uC785\uB2C8\uB2E4.")),
                List.of(),
                List.of(),
                "");

        assertTrue(result.content().contains("\uAC80\uC0C9 \uADFC\uAC70"));
        assertTrue(result.content().contains("\uC11C\uC6B8"));
        assertTrue(result.modelUsed().contains(":fallback:evidence"));
    }

    @Test
    void greetingQueriesClassifyAsNoEvidenceRequired() {
        // "안녕?" 영상 재현 시나리오 — 인사는 근거 검증 불필요로 분류되어
        // 최종 공개 경계의 보류 판정을 타지 않아야 한다.
        assertTrue(NoEvidenceChatFallback.isGreeting("안녕?"));
        assertTrue(NoEvidenceChatFallback.isGreeting("안녕하세요"));
        assertTrue(NoEvidenceChatFallback.isGreeting("hi"));
        assertTrue(NoEvidenceChatFallback.isGreeting("hello there"));
        assertFalse(NoEvidenceChatFallback.isGreeting(""));
        assertFalse(NoEvidenceChatFallback.isGreeting(null));
    }

    @Test
    void casualGreetingOnlyRejectsGreetingPrefixedRealQueries() {
        // 순수 인사만 casual 스킵 대상 — 인사 뒤 실제 질의는 정상 검색 경로를 유지.
        assertTrue(NoEvidenceChatFallback.isCasualGreetingOnly("안녕?"));
        assertTrue(NoEvidenceChatFallback.isCasualGreetingOnly("안녕하세요!"));
        assertTrue(NoEvidenceChatFallback.isCasualGreetingOnly("hi"));
        // "hi there"의 there는 잔여 실어 — 보수적으로 스킵하지 않는다.
        assertFalse(NoEvidenceChatFallback.isCasualGreetingOnly("hi there"));
        assertTrue(NoEvidenceChatFallback.isCasualGreetingOnly("반가워요"));
        assertFalse(NoEvidenceChatFallback.isCasualGreetingOnly("안녕, 오늘 주가 어때?"));
        assertFalse(NoEvidenceChatFallback.isCasualGreetingOnly("안녕하세요. RAG 구조 설명해줘"));
        assertFalse(NoEvidenceChatFallback.isCasualGreetingOnly("오늘 주가 어때?"));
        assertFalse(NoEvidenceChatFallback.isCasualGreetingOnly(null));
    }

    @Test
    void contentTextSegmentFailureAddsRedactedTraceBreadcrumb() {
        Content broken = mock(Content.class);
        when(broken.textSegment()).thenThrow(new IllegalStateException("raw private token"));
        when(broken.toString()).thenReturn("fallback public evidence");

        ChatResult result = NoEvidenceChatFallback.orEvidenceFallback(
                "question",
                "model-x",
                false,
                List.of(broken),
                List.of(),
                List.of(),
                "");

        assertTrue(result.content().contains("fallback public evidence"));
        assertEquals(1L, TraceStore.get("chat.llmFallback.evidenceText.suppressed.count"));
        assertEquals("evidenceText.textSegment", TraceStore.get("chat.llmFallback.evidenceText.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("chat.llmFallback.evidenceText.suppressed.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("raw private token"));
        assertFalse(trace.contains("IllegalStateException:"));
    }

    private static boolean looksMojibaked(String text) {
        return text.contains("??")
                || text.contains("\uFFFD")
                || text.contains("\u6FE1")
                || text.contains("\uF9DE");
    }
}
