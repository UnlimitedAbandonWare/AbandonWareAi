package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.data.document.Document;
import com.example.lms.service.rag.pre.CognitiveState;
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
    private final CognitiveState cognitiveState;
    private final List<Content> web;
    private final List<Content> rag;
    private final List<Document> localDocs;

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
        this.cognitiveState = b.cognitiveState;
        this.web = b.web;
        this.rag = b.rag;
        this.localDocs = b.localDocs;
        this.history = b.history;
        this.systemInstruction = b.systemInstruction;
        this.verbosityHint = b.verbosityHint;
        this.unsupportedClaims = b.unsupportedClaims;
        this.citationStyle = b.citationStyle;
        this.minWordCount = b.minWordCount;
        this.targetTokenBudgetOut = b.targetTokenBudgetOut;
        this.sectionSpec = b.sectionSpec;
        this.audience = b.audience;

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
    public Map<String, Set<String>> interactionRules() { return interactionRules; }
    public CognitiveState cognitiveState() { return cognitiveState; }
    public List<Content> web() { return web; }
    public List<Content> rag() { return rag; }
    public List<Document> localDocs() { return localDocs; }
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


    // --- Builder ---
    public static class Builder {
        private Integer minWordCount;
        private Integer targetTokenBudgetOut;
        private List<String> sectionSpec;
        private String audience;

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
        private Map<String, Set<String>> interactionRules;
        private CognitiveState cognitiveState;
        private List<Content> web;
        private List<Content> rag;
        private List<Document> localDocs;

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
        public Builder interactionRules(Map<String, Set<String>> m) { this.interactionRules = m; return this; }
        public Builder cognitiveState(CognitiveState cs) { this.cognitiveState = cs; return this; }
        public Builder web(List<Content> w) { this.web = w; return this; }
        public Builder rag(List<Content> r) { this.rag = r; return this; }

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


        public PromptContext build() { return new PromptContext(this); }
    }
}