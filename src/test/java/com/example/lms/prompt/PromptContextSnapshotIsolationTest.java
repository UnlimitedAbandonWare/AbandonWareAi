package com.example.lms.prompt;

import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.ensemble.SampledCandidate;
import com.example.lms.guard.GuardProfile;
import com.example.lms.learning.chat.LearningActorRole;
import com.example.lms.learning.chat.LearningSignal;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PromptContextSnapshotIsolationTest {
    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    private record ListCase<T>(String name, T item,
            BiConsumer<PromptContext.Builder, List<T>> setter,
            Function<PromptContext, List<T>> getter) {
        @Override public String toString() { return name; }
    }

    static Stream<ListCase<?>> collectionCases() {
        Content content = Content.from(TextSegment.from("synthetic evidence A"));
        return Stream.of(
                new ListCase<Content>("web", content, PromptContext.Builder::web, PromptContext::web),
                new ListCase<Content>("rag", content, PromptContext.Builder::rag, PromptContext::rag),
                new ListCase<Document>("localDocs", Document.from("synthetic document A"),
                        PromptContext.Builder::localDocs, PromptContext::localDocs),
                new ListCase<RagEvidenceMetadata>("evidence", evidence("A"),
                        PromptContext.Builder::evidence, PromptContext::evidence),
                new ListCase<String>("unsupportedClaims", "claim A",
                        PromptContext.Builder::unsupportedClaims, PromptContext::unsupportedClaims),
                new ListCase<String>("sectionSpec", "section A",
                        PromptContext.Builder::sectionSpec, PromptContext::sectionSpec),
                new ListCase<LearningSignal>("learningSignals", signal("A"),
                        PromptContext.Builder::learningSignals, PromptContext::learningSignals),
                new ListCase<SampledCandidate>("ensembleCandidates", candidate("A"),
                        PromptContext.Builder::ensembleCandidates, PromptContext::ensembleCandidates),
                new ListCase<String>("sourceUrls", "https://example.org/A",
                        PromptContext.Builder::sourceUrls, PromptContext::sourceUrls),
                new ListCase<String>("officialSources", "https://example.org/official-A",
                        PromptContext.Builder::officialSources, PromptContext::officialSources));
    }

    @ParameterizedTest(name = "snapshot preserves {0}")
    @MethodSource("collectionCases")
    <T> void listsSnapshotInputsAndRejectGetterMutation(ListCase<T> c) {
        List<T> input = new ArrayList<>(Arrays.asList(c.item(), null, c.item()));
        List<T> expected = new ArrayList<>(input);
        PromptContext.Builder builder = PromptContext.builder();
        c.setter().accept(builder, input);
        PromptContext ctx = builder.build();
        input.set(0, null);
        assertEquals(expected, c.getter().apply(ctx), c.name() + " changed after input.set");
        input.clear();
        input.add(null);
        List<T> actual = c.getter().apply(ctx);
        assertEquals(expected, actual, c.name() + " changed after input.clear/add");
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
        assertThrows(UnsupportedOperationException.class, () -> actual.add(c.item()), c.name());
        assertThrows(UnsupportedOperationException.class, () -> actual.set(0, null), c.name());
        assertThrows(UnsupportedOperationException.class, actual::clear, c.name());
    }

    @Test
    void interactionRulesSnapshotOuterMapAndInnerHashSet() {
        Set<String> inner = new HashSet<>(Arrays.asList("allow", null, "deny"));
        List<String> expectedOrder = new ArrayList<>(inner);
        Map<String, Set<String>> input = new LinkedHashMap<>();
        input.put("policy", inner);
        input.put(null, null);
        PromptContext ctx = PromptContext.builder().interactionRules(input).build();
        inner.clear();
        inner.add("B");
        assertEquals(expectedOrder, new ArrayList<>(ctx.interactionRules().get("policy")));
        input.clear();
        input.put("B", Set.of("B"));
        assertEquals(Arrays.asList("policy", null), new ArrayList<>(ctx.interactionRules().keySet()));
        assertTrue(ctx.interactionRules().containsKey(null));
        assertNull(ctx.interactionRules().get(null));
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.interactionRules().put("B", Set.of("B")));
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.interactionRules().get("policy").add("B"));
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.interactionRules().entrySet().iterator().next().setValue(Set.of("B")));
    }

    @Test
    void interactionRulesPreserveLinkedSetOrderAndNullItems() {
        Set<String> input = new LinkedHashSet<>(Arrays.asList("second", null, "first"));
        PromptContext ctx = PromptContext.builder().interactionRules(Map.of("policy", input)).build();
        assertEquals(Arrays.asList("second", null, "first"),
                new ArrayList<>(ctx.interactionRules().get("policy")));
        assertThrows(UnsupportedOperationException.class, () -> ctx.interactionRules().get("policy").clear());
    }

    @Test
    void nullAndEmptyCollectionContractsRemainCompatible() {
        PromptContext ctx = PromptContext.builder().build();
        assertNull(ctx.interactionRules());
        assertNull(ctx.unsupportedClaims());
        assertNull(ctx.sectionSpec());
        for (List<?> list : List.of(ctx.web(), ctx.rag(), ctx.localDocs(), ctx.evidence(),
                ctx.learningSignals(), ctx.ensembleCandidates(), ctx.sourceUrls(), ctx.officialSources())) {
            assertTrue(list.isEmpty());
        }
        PromptContext empty = PromptContext.builder().interactionRules(new LinkedHashMap<>())
                .unsupportedClaims(new ArrayList<>()).sectionSpec(new ArrayList<>()).build();
        assertTrue(empty.interactionRules().isEmpty());
        assertTrue(empty.unsupportedClaims().isEmpty());
        assertTrue(empty.sectionSpec().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> empty.interactionRules().put("x", Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> empty.unsupportedClaims().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> empty.sectionSpec().add("x"));
        assertSame(empty.ensembleCandidates(), empty.getEnsembleCandidates());
        assertSame(empty.sourceUrls(), empty.getSourceUrls());
        assertSame(empty.officialSources(), empty.getOfficialSources());
    }

    @Test
    void reusedBuilderAndDerivedContextPreserveOriginalFieldsAndPrompts() throws Exception {
        List<Content> web = new ArrayList<>(List.of(Content.from(TextSegment.from("SNAPSHOT_A"))));
        List<Content> rag = new ArrayList<>(web);
        List<Document> docs = new ArrayList<>(List.of(Document.from("document A")));
        List<RagEvidenceMetadata> metadata = new ArrayList<>(List.of(evidence("A")));
        List<String> claims = new ArrayList<>(List.of("claim A"));
        List<String> sections = new ArrayList<>(List.of("section A"));
        List<String> urls = new ArrayList<>(List.of("https://example.org/A"));
        List<String> official = new ArrayList<>(List.of("https://example.org/official-A"));
        List<LearningSignal> learning = new ArrayList<>(List.of(signal("A")));
        List<SampledCandidate> candidates = new ArrayList<>(List.of(candidate("A")));
        Set<String> rule = new LinkedHashSet<>(List.of("rule A"));
        Map<String, Set<String>> rules = new LinkedHashMap<>(Map.of("policy", rule));
        Map<String, Double> refinement = new LinkedHashMap<>(Map.of("keptRatio", 0.8d));
        PromptContext.Builder builder = populatedBuilder().web(web).rag(rag).localDocs(docs).evidence(metadata)
                .unsupportedClaims(claims).sectionSpec(sections).sourceUrls(urls).officialSources(official)
                .learningSignals(learning).ensembleCandidates(candidates).interactionRules(rules)
                .contextRefinementSignals(refinement);
        PromptContext a = builder.build();
        PromptBuilder prompts = new StandardPromptBuilder();
        String baseline = prompts.build(a);
        String instructions = prompts.buildInstructions(a);
        Map<String, Object> original = fields(a);
        for (List<?> list : List.of(web, rag, docs, metadata, claims, sections, urls, official, learning, candidates)) {
            list.clear();
        }
        web.add(Content.from(TextSegment.from("SNAPSHOT_B")));
        rule.clear();
        rule.add("rule B");
        rules.clear();
        refinement.clear();
        PromptContext b = builder.memory("memory B").build();
        assertEquals(baseline, prompts.build(a), "prompt changed after input mutation/builder reuse");
        assertEquals(instructions, prompts.buildInstructions(a), "instructions changed after input mutation");
        assertFalse(prompts.build(a).contains("SNAPSHOT_B"));
        assertTrue(prompts.build(b).contains("SNAPSHOT_B"));
        assertEquals(original, fields(a.toBuilder().build()), "toBuilder dropped a field");
        List<Content> derivedInput = new ArrayList<>(b.web());
        PromptContext derived = a.toBuilder().memory("derived memory").web(derivedInput).build();
        derivedInput.clear();
        assertEquals(b.web(), derived.web());
        assertEquals("derived memory", derived.memory());
        assertEquals("memory A", a.memory());
        assertEquals(original, fields(a), "original changed after derived context");
        assertEquals(baseline, prompts.build(a));
        assertEquals(instructions, prompts.buildInstructions(a));
        assertThrows(UnsupportedOperationException.class, () -> a.contextRefinementSignals().put("x", 1d));
    }

    private static PromptContext.Builder populatedBuilder() {
        return PromptContext.builder().id("id A").title("title A").snippet("snippet A").source("source A")
                .score(0.7d).rank(2).userQuery("synthetic question A").lastAssistantAnswer("answer A")
                .subject("subject A").fileContext("file A").ragEnabled(true).memory("memory A")
                .intent("intent A").domain("domain A").queryDomain(QueryDomain.STUDY)
                .guardProfile(GuardProfile.STRICT).visionMode(VisionMode.STRICT).answerMode(AnswerMode.FACT)
                .memoryMode(MemoryMode.FULL).history("history A").systemInstruction("instruction A")
                .verbosityHint("concise").citationStyle("inline").minWordCount(20).targetTokenBudgetOut(250)
                .audience("audience A").resourceTier("tier A").resourceValueScore(0.1d)
                .resourceOptimismScore(0.2d).resourceRiskAdjustedConfidence(0.3d)
                .resourceRewriteTemperature(0.4d).resourceSearchRangeMultiplier(0.5d)
                .learningRole(LearningActorRole.STUDENT).learningContextSummary("learning A")
                .ensembleJudgeMode(true).contextRefinementSummary("refinement A");
    }

    private static Map<String, Object> fields(PromptContext ctx) throws IllegalAccessException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field field : PromptContext.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                Object value = field.get(ctx);
                if (value instanceof List<?> list) value = new ArrayList<>(list);
                else if (value instanceof Map<?, ?> map) value = new LinkedHashMap<>(map);
                result.put(field.getName(), value);
            }
        }
        return result;
    }

    private static RagEvidenceMetadata evidence(String marker) {
        return new RagEvidenceMetadata(marker, "web", "title " + marker, "https://example.org/" + marker,
                null, null, null, 1, 0.9d, "synthetic");
    }

    private static LearningSignal signal(String marker) { return new LearningSignal("progress", marker, marker, 0.8d); }
    private static SampledCandidate candidate(String marker) {
        return new SampledCandidate(marker, "candidate " + marker, 0.4d, 0.9d, 0.8d, 0.1d, null);
    }
}
