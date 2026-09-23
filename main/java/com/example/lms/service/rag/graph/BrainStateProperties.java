package com.example.lms.service.rag.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.brain-state")
public class BrainStateProperties {

    private boolean enabled = true;
    private final Chunking chunking = new Chunking();
    private final Indexing indexing = new Indexing();
    private final Neo4j neo4j = new Neo4j();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Chunking getChunking() {
        return chunking;
    }

    public Indexing getIndexing() {
        return indexing;
    }

    public Neo4j getNeo4j() {
        return neo4j;
    }

    public static class Chunking {
        private int maxChunkSize = 400;
        private int overlap = 50;
        private int maxEntitiesPerChunk = 20;

        public int getMaxChunkSize() {
            return maxChunkSize;
        }

        public void setMaxChunkSize(int maxChunkSize) {
            this.maxChunkSize = Math.max(128, maxChunkSize);
        }

        public int getOverlap() {
            return overlap;
        }

        public void setOverlap(int overlap) {
            this.overlap = Math.max(0, overlap);
        }

        public int getMaxEntitiesPerChunk() {
            return maxEntitiesPerChunk;
        }

        public void setMaxEntitiesPerChunk(int maxEntitiesPerChunk) {
            this.maxEntitiesPerChunk = Math.max(1, Math.min(maxEntitiesPerChunk, 100));
        }
    }

    public static class Indexing {
        private boolean enabled = true;
        private boolean captureChatWorkflow = true;
        private int maxEntitiesPerDomain = 200;
        private long snapshotCacheTtlSeconds = 30;
        private boolean meaningfulGateEnabled = true;
        private int minEntitiesPerChunk = 1;
        private double minChunkConfidence = 0.50d;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isCaptureChatWorkflow() {
            return captureChatWorkflow;
        }

        public void setCaptureChatWorkflow(boolean captureChatWorkflow) {
            this.captureChatWorkflow = captureChatWorkflow;
        }

        public int getMaxEntitiesPerDomain() {
            return maxEntitiesPerDomain;
        }

        public void setMaxEntitiesPerDomain(int maxEntitiesPerDomain) {
            this.maxEntitiesPerDomain = Math.max(1, Math.min(maxEntitiesPerDomain, 10_000));
        }

        public long getSnapshotCacheTtlSeconds() {
            return snapshotCacheTtlSeconds;
        }

        public void setSnapshotCacheTtlSeconds(long snapshotCacheTtlSeconds) {
            this.snapshotCacheTtlSeconds = Math.max(0, snapshotCacheTtlSeconds);
        }

        public boolean isMeaningfulGateEnabled() {
            return meaningfulGateEnabled;
        }

        public void setMeaningfulGateEnabled(boolean meaningfulGateEnabled) {
            this.meaningfulGateEnabled = meaningfulGateEnabled;
        }

        public int getMinEntitiesPerChunk() {
            return minEntitiesPerChunk;
        }

        public void setMinEntitiesPerChunk(int minEntitiesPerChunk) {
            this.minEntitiesPerChunk = Math.max(0, Math.min(minEntitiesPerChunk, 100));
        }

        public double getMinChunkConfidence() {
            return minChunkConfidence;
        }

        public void setMinChunkConfidence(double minChunkConfidence) {
            if (Double.isNaN(minChunkConfidence) || Double.isInfinite(minChunkConfidence)) {
                this.minChunkConfidence = 0.50d;
                return;
            }
            this.minChunkConfidence = Math.max(0.0d, Math.min(1.0d, minChunkConfidence));
        }
    }

    public static class Neo4j {
        private int ingestBatchSize = 10;

        public int getIngestBatchSize() {
            return ingestBatchSize;
        }

        public void setIngestBatchSize(int ingestBatchSize) {
            this.ingestBatchSize = Math.max(1, Math.min(ingestBatchSize, 200));
        }
    }
}
