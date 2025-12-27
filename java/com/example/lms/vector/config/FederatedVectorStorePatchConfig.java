package com.example.lms.vector.config;

import com.example.lms.vector.FederatedEmbeddingStore;
import com.example.lms.vector.FederatedEmbeddingStore.NamedStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bridges individual EmbeddingStore beans (Pinecone, in‑memory fallback, etc.)
 * into the {@link FederatedEmbeddingStore} by providing the List&lt;NamedStore&gt;
 * constructor argument.
 *
 * IMPORTANT: this configuration must NOT create a circular dependency with
 * FederatedEmbeddingStore itself.  To avoid this we only depend on explicitly
 * named underlying stores and never on the generic List&lt;EmbeddingStore&lt;TextSegment&gt;&gt;
 * or the primary EmbeddingStore bean.
 */
@Configuration
@ConditionalOnClass(FederatedEmbeddingStore.class)
public class FederatedVectorStorePatchConfig {

    private static final Logger log = LoggerFactory.getLogger(FederatedVectorStorePatchConfig.class);

    /**
     * Provide the list of underlying stores used by {@link FederatedEmbeddingStore}.
     *
     * The resolution strategy is:
     *  - Prefer the composite {@code embeddingStore} bean when present
     *    (used when retrieval.vector.enabled=false).
     *  - Otherwise fall back to vendor specific stores such as
     *    {@code pineconeEmbeddingStore} or {@code inMemoryEmbeddingStore}.
     *
     * Because the parameters are injected as {@link ObjectProvider}s with
     * {@link Qualifier}s, Spring never needs to create the FederatedEmbeddingStore
     * itself in order to resolve this bean, which breaks the circular dependency.
     */
    @Bean
    @ConditionalOnMissingBean(name = "federatedEmbeddingStores")
    public List<NamedStore> federatedEmbeddingStores(
            @Qualifier("embeddingStore") ObjectProvider<EmbeddingStore<TextSegment>> compositeStore,
            @Qualifier("pineconeEmbeddingStore") ObjectProvider<EmbeddingStore<TextSegment>> pineconeStore,
            @Qualifier("inMemoryEmbeddingStore") ObjectProvider<EmbeddingStore<TextSegment>> inMemoryStore
    ) {
        List<EmbeddingStore<TextSegment>> upstream = new ArrayList<>();

        // 1) Prefer the composite EmbeddingStore when available
        EmbeddingStore<TextSegment> composite = compositeStore.getIfAvailable();
        if (composite != null) {
            upstream.add(composite);
        } else {
            // 2) Otherwise fall back to vendor specific stores
            EmbeddingStore<TextSegment> pinecone = pineconeStore.getIfAvailable();
            if (pinecone != null) {
                upstream.add(pinecone);
            }
        }

        // 3) Optional in‑memory fallback (only when it is a distinct bean)
        EmbeddingStore<TextSegment> inMemory = inMemoryStore.getIfAvailable();
        if (inMemory != null && upstream.stream().noneMatch(s -> s == inMemory)) {
            upstream.add(inMemory);
        }

        if (upstream.isEmpty()) {
            log.warn("[FederatedVectorStorePatchConfig] No upstream EmbeddingStore beans discovered; " +
                    "FederatedEmbeddingStore will effectively be disabled.");
            return Collections.emptyList();
        }

        List<NamedStore> namedStores = new ArrayList<>();
        if (upstream.size() == 1) {
            namedStores.add(new NamedStore("default", upstream.get(0)));
        } else {
            for (int i = 0; i < upstream.size(); i++) {
                String id = "store-" + i;
                namedStores.add(new NamedStore(id, upstream.get(i)));
            }
        }

        log.info("[FederatedVectorStorePatchConfig] FederatedEmbeddingStore bridge initialised with {} store(s): {}",
                namedStores.size(),
                namedStores.stream().map(NamedStore::id).collect(Collectors.joining(", ")));

        return Collections.unmodifiableList(namedStores);
    }
}
