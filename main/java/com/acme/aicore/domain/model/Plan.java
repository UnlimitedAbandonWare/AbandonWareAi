package com.acme.aicore.domain.model;


/**
 * Encapsulates the plan for executing a query through the retrieval and
 * generation pipeline.  A plan describes whether web search and vector
 * search should be invoked, how many web providers to fan out to, whether
 * reranking should be applied, and the parameters for each stage.  It is
 * constructed by the {@link com.acme.aicore.app.QueryPlanner}.
 */
public class Plan {
    private boolean useWeb;
    private boolean useVector;
    private int webFanout;
    private int rerankTopN;
    private RankingParams rankingParams;
    private RerankParams rerankParams;
    private PromptParams promptParams;
    private GenerationParams generationParams;
    private boolean stream;

    private Plan() {}

    public boolean useWeb() { return useWeb; }
    public boolean useVector() { return useVector; }
    public int webFanout() { return webFanout; }
    public int rerankTopN() { return rerankTopN; }
    public RankingParams rankingParams() { return rankingParams; }
    public RerankParams rerankParams() { return rerankParams; }
    public PromptParams promptParams() { return promptParams; }
    public GenerationParams generationParams() { return generationParams; }
    public boolean stream() { return stream; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Plan p = new Plan();
        public Builder useWeb(boolean v) { p.useWeb = v; return this; }
        public Builder useVector(boolean v) { p.useVector = v; return this; }
        public Builder webFanout(int v) { p.webFanout = v; return this; }
        public Builder rerankTopN(int v) { p.rerankTopN = v; return this; }
        public Builder rankingParams(RankingParams v) { p.rankingParams = v; return this; }
        public Builder rerankParams(RerankParams v) { p.rerankParams = v; return this; }
        public Builder promptParams(PromptParams v) { p.promptParams = v; return this; }
        public Builder generationParams(GenerationParams v) { p.generationParams = v; return this; }
        public Builder stream(boolean v) { p.stream = v; return this; }
        public Plan build() { return p; }
    }
}