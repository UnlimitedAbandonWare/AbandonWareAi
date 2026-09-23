package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import com.example.lms.ensemble.SampledCandidate;
import dev.langchain4j.data.document.Document;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.service.rag.pre.CognitiveState;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.learning.chat.LearningActorRole;
import com.example.lms.learning.chat.LearningSignal;
import java.util.List;
import java.util.Map;
import java.util.Set;




/**
 * Backward-compatible PromptContext supporting legacy builder() and getters
 * used across the codebase (ctx.web(), ctx.rag(), ctx.userQuery()/* ... *&#47;).
 * This class also retains the lightweight evidence fields for StandardPromptBuilder.
 */
public class PromptContext {

    // --- Evidence item fields (used by StandardPromptBuilder) ---
    public final String id;
    public final String title;
    public final String snippet;
    public final String source;
    public final double score;
    public final int rank;

    // --- Rich context fields (nullable) ---
    private final String userQuery;
    private final String lastAssistantAnswer;
    private final String subject;
    private final String fileContext;
    private final Boolean ragEnabled;
    private final String memory;
    private final String intent;
    private final String domain;
    private final Map<String, Set<String>> interactionRules;
    private final InteractionEvidencePolicy.Decision interactionPolicyDecision;
    private final ConversationFrameV1 conversationFrame;
    private final CognitiveState cognitiveState;
    private final List<Content> web;
    private final List<Content> rag;
    private final List<Document> localDocs;
    private final List<RagEvidenceMetadata> evidence;
    private final QueryDomain queryDomain;
    private final GuardProfile guardProfile;
    private final VisionMode visionMode;
    private final AnswerMode answerMode;
    private final MemoryMode memoryMode;

    // Newly added: history/systemInstruction/verbosityHint/unsupportedClaims
    private final String history;
    private final String systemInstruction;
    private final String verbosityHint;
    private final List<String> unsupportedClaims;
    private final String citationStyle;
    // Back-compat hint fields
    private final Integer minWordCount;           // Nullable
    private final Integer targetTokenBudgetOut;   // Nullable
    private final List<String> sectionSpec;       // Nullable
    private final String audience;                // Nullable
    private final String resourceTier;            // Nullable
    private final Double resourceValueScore;      // Nullable
    private final Double resourceOptimismScore;   // Nullable
    private final Double resourceRiskAdjustedConfidence; // Nullable
    private final Double resourceRewriteTemperature;     // Nullable
    private final Double resourceSearchRangeMultiplier;  // Nullable
    private final LearningActorRole learningRole;
    private final List<LearningSignal> learningSignals;
    private final String learningContextSummary;
    private final List<SampledCandidate> ensembleCandidates;
    private final boolean ensembleJudgeMode;
    private final String contextRefinementSummary;
    private final Map<String, Double> contextRefinementSignals;
    private final List<String> sourceUrls;
    private final List<String> officialSources;


    private PromptContext(Builder b) {
        this.id = b.id;
        this.title = b.title;
        this.snippet = b.snippet;
        this.source = b.source;
        this.score = b.score;
        this.rank = b.rank;

        this.userQuery = b.userQuery;
        this.lastAssistantAnswer = b.lastAssistantAnswer;
        this.subject = b.subject;
        this.fileContext = b.fileContext;
        this.ragEnabled = b.ragEnabled;
        this.memory = b.memory;
        this.intent = b.intent;
        this.domain = b.domain;
        this.interactionRules = b.interactionRules;
        this.interactionPolicyDecision = b.interactionPolicyDecision == null
                ? InteractionEvidencePolicy.offDecision()
                : b.interactionPolicyDecision;
        this.conversationFrame = b.conversationFrame == null
                ? ConversationFrameV1.off(false)
                : b.conversationFrame;
        this.cognitiveState = b.cognitiveState;
        // null-safe: prompt builders and renderers assume these are non-null
        this.web = b.web != null ? b.web : java.util.Collections.emptyList();
        this.rag = b.rag != null ? b.rag : java.util.Collections.emptyList();
        this.localDocs = b.localDocs != null ? b.localDocs : java.util.Collections.emptyList();
        this.evidence = b.evidence != null ? b.evidence : java.util.Collections.emptyList();
        this.queryDomain = b.queryDomain;
        this.guardProfile = b.guardProfile;
        this.visionMode = b.visionMode;
        this.answerMode = b.answerMode;
        this.memoryMode = b.memoryMode;
        this.history = b.history;
        this.systemInstruction = b.systemInstruction;
        this.verbosityHint = b.verbosityHint;
        this.unsupportedClaims = b.unsupportedClaims;
        this.citationStyle = b.citationStyle;
        this.minWordCount = b.minWordCount;
        this.targetTokenBudgetOut = b.targetTokenBudgetOut;
        this.sectionSpec = b.sectionSpec;
        this.audience = b.audience;
        this.resourceTier = b.resourceTier;
        this.resourceValueScore = b.resourceValueScore;
        this.resourceOptimismScore = b.resourceOptimismScore;
        this.resourceRiskAdjustedConfidence = b.resourceRiskAdjustedConfidence;
        this.resourceRewriteTemperature = b.resourceRewriteTemperature;
        this.resourceSearchRangeMultiplier = b.resourceSearchRangeMultiplier;
        this.learningRole = b.learningRole != null ? b.learningRole : LearningActorRole.ANONYMOUS;
        this.learningSignals = b.learningSignals != null ? b.learningSignals : java.util.Collections.emptyList();
        this.learningContextSummary = b.learningContextSummary;
        this.ensembleCandidates = b.ensembleCandidates != null ? b.ensembleCandidates : java.util.Collections.emptyList();
        this.ensembleJudgeMode = b.ensembleJudgeMode;
        this.contextRefinementSummary = b.contextRefinementSummary;
        this.contextRefinementSignals = b.contextRefinementSignals != null
                ? java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(b.contextRefinementSignals))
                : java.util.Collections.emptyMap();
        this.sourceUrls = b.sourceUrls != null ? b.sourceUrls : java.util.Collections.emptyList();
        this.officialSources = b.officialSources != null ? b.officialSources : java.util.Collections.emptyList();

    }

    // --- Backward-compatible accessors ---
    public static Builder builder() { return new Builder(); }

    public String userQuery() { return userQuery; }
    public String lastAssistantAnswer() { return lastAssistantAnswer; }
    public String subject() { return subject; }
    public String fileContext() { return fileContext; }
    public Boolean ragEnabled() { return ragEnabled; }
    public String memory() { return memory; }
    public String intent() { return intent; }
    public String domain() { return domain; }
    public QueryDomain queryDomain() { return queryDomain; }
    public GuardProfile guardProfile() { return guardProfile; }
    public VisionMode visionMode() { return visionMode; }
    public AnswerMode answerMode() { return answerMode; }
    public MemoryMode memoryMode() { return memoryMode; }
    public Map<String, Set<String>> interactionRules() { return interactionRules; }
    public InteractionEvidencePolicy.Decision interactionPolicyDecision() { return interactionPolicyDecision; }
    public ConversationFrameV1 conversationFrame() { return conversationFrame; }
    public CognitiveState cognitiveState() { return cognitiveState; }
    public List<Content> web() { return web; }
    public List<Content> rag() { return rag; }
    public List<Document> localDocs() { return localDocs; }
    public List<RagEvidenceMetadata> evidence() { return evidence; }
    // Newly added getters
    public String history() { return history; }
    public String systemInstruction() { return systemInstruction; }
    public String verbosityHint() { return verbosityHint; }
    public List<String> unsupportedClaims() { return unsupportedClaims; }
    public String citationStyle() { return citationStyle; }
    public Integer minWordCount() { return minWordCount; }
    public Integer targetTokenBudgetOut() { return targetTokenBudgetOut; }
    public List<String> sectionSpec() { return sectionSpec; }
    public String audience() { return audience; }
    public String resourceTier() { return resourceTier; }
    public Double resourceValueScore() { return resourceValueScore; }
    public Double resourceOptimismScore() { return resourceOptimismScore; }
    public Double resourceRiskAdjustedConfidence() { return resourceRiskAdjustedConfidence; }
    public Double resourceRewriteTemperature() { return resourceRewriteTemperature; }
    public Double resourceSearchRangeMultiplier() { return resourceSearchRangeMultiplier; }
    public LearningActorRole learningRole() { return learningRole; }
    public List<LearningSignal> learningSignals() { return learningSignals; }
    public String learningContextSummary() { return learningContextSummary; }
    public List<SampledCandidate> ensembleCandidates() { return ensembleCandidates; }
    public List<SampledCandidate> getEnsembleCandidates() { return ensembleCandidates; }
    public boolean ensembleJudgeMode() { return ensembleJudgeMode; }
    public boolean isEnsembleJudgeMode() { return ensembleJudgeMode; }
    public String contextRefinementSummary() { return contextRefinementSummary; }
    public Map<String, Double> contextRefinementSignals() { return contextRefinementSignals; }
    public List<String> sourceUrls() { return sourceUrls; }
    public List<String> getSourceUrls() { return sourceUrls; }
    public List<String> officialSources() { return officialSources; }
    public List<String> getOfficialSources() { return officialSources; }

    public Builder toBuilder() {
        return builder()
                .id(id)
                .title(title)
                .snippet(snippet)
                .source(source)
                .score(score)
                .rank(rank)
                .userQuery(userQuery)
                .lastAssistantAnswer(lastAssistantAnswer)
                .subject(subject)
                .fileContext(fileContext)
                .ragEnabled(ragEnabled)
                .memory(memory)
                .intent(intent)
                .domain(domain)
                .queryDomain(queryDomain)
                .guardProfile(guardProfile)
                .visionMode(visionMode)
                .answerMode(answerMode)
                .memoryMode(memoryMode)
                .interactionRules(interactionRules)
                .interactionPolicyDecision(interactionPolicyDecision)
                .conversationFrame(conversationFrame)
                .cognitiveState(cognitiveState)
                .web(web)
                .rag(rag)
                .localDocs(localDocs)
                .evidence(evidence)
                .history(history)
                .systemInstruction(systemInstruction)
                .verbosityHint(verbosityHint)
                .unsupportedClaims(unsupportedClaims)
                .citationStyle(citationStyle)
                .minWordCount(minWordCount)
                .targetTokenBudgetOut(targetTokenBudgetOut)
                .sectionSpec(sectionSpec)
                .audience(audience)
                .resourceTier(resourceTier)
                .resourceValueScore(resourceValueScore)
                .resourceOptimismScore(resourceOptimismScore)
                .resourceRiskAdjustedConfidence(resourceRiskAdjustedConfidence)
                .resourceRewriteTemperature(resourceRewriteTemperature)
                .resourceSearchRangeMultiplier(resourceSearchRangeMultiplier)
                .learningRole(learningRole)
                .learningSignals(learningSignals)
                .learningContextSummary(learningContextSummary)
                .ensembleCandidates(ensembleCandidates)
                .ensembleJudgeMode(ensembleJudgeMode)
                .contextRefinementSummary(contextRefinementSummary)
                .contextRefinementSignals(contextRefinementSignals)
                .sourceUrls(sourceUrls)
                .officialSources(officialSources);
    }

    // --- Builder ---
    public static class Builder {
        private Integer minWordCount;
        private Integer targetTokenBudgetOut;
        private List<String> sectionSpec;
        private String audience;
        private String resourceTier;
        private Double resourceValueScore;
        private Double resourceOptimismScore;
        private Double resourceRiskAdjustedConfidence;
        private Double resourceRewriteTemperature;
        private Double resourceSearchRangeMultiplier;
        private LearningActorRole learningRole;
        private List<LearningSignal> learningSignals;
        private String learningContextSummary;
        private List<SampledCandidate> ensembleCandidates;
        private boolean ensembleJudgeMode;
        private String contextRefinementSummary;
        private Map<String, Double> contextRefinementSignals;
        private List<String> sourceUrls;
        private List<String> officialSources;

        private String id;
        private String title;
        private String snippet;
        private String source;
        private double score;
        private int rank;

        private String userQuery;
        private String lastAssistantAnswer;
        private String subject;
        private String fileContext;
        private Boolean ragEnabled;
        private String memory;
        private String intent;
        private String domain;
        private QueryDomain queryDomain;
        private GuardProfile guardProfile;
        private VisionMode visionMode;
        private AnswerMode answerMode = AnswerMode.BALANCED;
        private MemoryMode memoryMode = MemoryMode.HYBRID;
        private Map<String, Set<String>> interactionRules;
        private InteractionEvidencePolicy.Decision interactionPolicyDecision = InteractionEvidencePolicy.offDecision();
        private ConversationFrameV1 conversationFrame = ConversationFrameV1.off(false);
        private CognitiveState cognitiveState;
        private List<Content> web;
        private List<Content> rag;
        private List<Document> localDocs;
        private List<RagEvidenceMetadata> evidence;

        // Newly added builder fields
        private String history;
        private String systemInstruction;
        private String verbosityHint;
        private List<String> unsupportedClaims;
        private String citationStyle;

        public Builder id(String id) { this.id = id; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder snippet(String snippet) { this.snippet = snippet; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder score(double score) { this.score = score; return this; }
        public Builder rank(int rank) { this.rank = rank; return this; }
        public Builder localDocs(List<Document> docs) { this.localDocs = docs; return this; }

        public Builder userQuery(String s) { this.userQuery = s; return this; }
        public Builder lastAssistantAnswer(String s) { this.lastAssistantAnswer = s; return this; }
        public Builder subject(String s) { this.subject = s; return this; }
        public Builder fileContext(String s) { this.fileContext = s; return this; }
        public Builder ragEnabled(Boolean v) { this.ragEnabled = v; return this; }
        public Builder memory(String s) { this.memory = s; return this; }
        public Builder intent(String s) { this.intent = s; return this; }
        public Builder domain(String s) { this.domain = s; return this; }
        public Builder queryDomain(QueryDomain d) { this.queryDomain = d; return this; }
        public Builder guardProfile(GuardProfile p) { this.guardProfile = p; return this; }
        public Builder visionMode(VisionMode v) { this.visionMode = v; return this; }
        public Builder answerMode(AnswerMode m) { this.answerMode = m; return this; }
        public Builder memoryMode(MemoryMode m) { this.memoryMode = m; return this; }
        public Builder interactionRules(Map<String, Set<String>> m) { this.interactionRules = m; return this; }
        public Builder interactionPolicyDecision(InteractionEvidencePolicy.Decision decision) {
            this.interactionPolicyDecision = decision;
            return this;
        }
        public Builder conversationFrame(ConversationFrameV1 frame) {
            this.conversationFrame = frame;
            return this;
        }
        public Builder cognitiveState(CognitiveState cs) { this.cognitiveState = cs; return this; }
        public Builder web(List<Content> w) { this.web = w; return this; }
        public Builder rag(List<Content> r) { this.rag = r; return this; }
        public Builder evidence(List<RagEvidenceMetadata> e) { this.evidence = e; return this; }

        // Newly added builder methods to satisfy legacy callers
        public Builder history(String s) { this.history = s; return this; }
        public Builder systemInstruction(String s) { this.systemInstruction = s; return this; }
        public Builder verbosityHint(String s) { this.verbosityHint = s; return this; }
        public Builder unsupportedClaims(List<String> list) { this.unsupportedClaims = list; return this; }
        public Builder citationStyle(String s) { this.citationStyle = s; return this; }
        // Newer builder methods for back-compat with legacy callers
        public Builder minWordCount(Integer m) { this.minWordCount = m; return this; }
        public Builder minWordCount(int m) { this.minWordCount = Integer.valueOf(m); return this; }
        public Builder targetTokenBudgetOut(Integer m) { this.targetTokenBudgetOut = m; return this; }
        public Builder targetTokenBudgetOut(int m) { this.targetTokenBudgetOut = Integer.valueOf(m); return this; }
        public Builder sectionSpec(List<String> s) { this.sectionSpec = s; return this; }
        public Builder audience(String a) { this.audience = a; return this; }
        public Builder resourceTier(String s) { this.resourceTier = s; return this; }
        public Builder resourceValueScore(Double d) { this.resourceValueScore = d; return this; }
        public Builder resourceValueScore(double d) { this.resourceValueScore = Double.valueOf(d); return this; }
        public Builder resourceOptimismScore(Double d) { this.resourceOptimismScore = d; return this; }
        public Builder resourceOptimismScore(double d) { this.resourceOptimismScore = Double.valueOf(d); return this; }
        public Builder resourceRiskAdjustedConfidence(Double d) { this.resourceRiskAdjustedConfidence = d; return this; }
        public Builder resourceRiskAdjustedConfidence(double d) { this.resourceRiskAdjustedConfidence = Double.valueOf(d); return this; }
        public Builder resourceRewriteTemperature(Double d) { this.resourceRewriteTemperature = d; return this; }
        public Builder resourceRewriteTemperature(double d) { this.resourceRewriteTemperature = Double.valueOf(d); return this; }
        public Builder resourceSearchRangeMultiplier(Double d) { this.resourceSearchRangeMultiplier = d; return this; }
        public Builder resourceSearchRangeMultiplier(double d) { this.resourceSearchRangeMultiplier = Double.valueOf(d); return this; }
        public Builder learningRole(LearningActorRole role) { this.learningRole = role; return this; }
        public Builder learningSignals(List<LearningSignal> signals) { this.learningSignals = signals; return this; }
        public Builder learningContextSummary(String summary) { this.learningContextSummary = summary; return this; }
        public Builder ensembleCandidates(List<SampledCandidate> candidates) { this.ensembleCandidates = candidates; return this; }
        public Builder ensembleJudgeMode(boolean enabled) { this.ensembleJudgeMode = enabled; return this; }
        public Builder contextRefinementSummary(String summary) { this.contextRefinementSummary = summary; return this; }
        public Builder contextRefinementSignals(Map<String, Double> signals) { this.contextRefinementSignals = signals; return this; }
        public Builder sourceUrls(List<String> urls) { this.sourceUrls = urls; return this; }
        public Builder officialSources(List<String> urls) { this.officialSources = urls; return this; }


        public PromptContext build() { return new PromptContext(this); }
    }
}
