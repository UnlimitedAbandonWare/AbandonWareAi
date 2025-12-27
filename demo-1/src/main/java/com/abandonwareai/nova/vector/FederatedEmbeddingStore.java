package com.abandonwareai.nova.vector;

import java.util.List;

public interface FederatedEmbeddingStore {
    void upsert(List<EmbeddingDocument> docs);
}