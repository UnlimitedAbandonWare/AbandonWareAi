package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.QueryUtils;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VectorDbHandlerGenericStoreTest {
    @Test void vectorOutageRetainsExactSourceObjectsAndContinuesOnceWithoutRepeatingWeb() {
        var service=mock(LangChainRAGService.class);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(service.asContentRetriever("idx")).thenReturn(q->{calls.incrementAndGet();throw new IllegalStateException("synthetic vector outage");});
        var source=Content.from(TextSegment.from("synthetic evidence",dev.langchain4j.data.document.Metadata.from("sourceId","web-7")));
        var acc=new ArrayList<>(List.of(source));var continued=new java.util.concurrent.atomic.AtomicInteger();
        var handler=new VectorDbHandler(service,"idx",new OrchestrationGate(null));
        handler.linkWith(new AbstractRetrievalHandler(){protected boolean doHandle(Query q,List<Content> values){continued.incrementAndGet();return false;}});
        handler.handle(new Query("synthetic"),acc);
        assertEquals(1,calls.get());assertEquals(1,continued.get());assertEquals(1,acc.size());assertSame(source,acc.get(0));
        assertEquals("web-7",acc.get(0).textSegment().metadata().getString("sourceId"));verify(service,times(1)).asContentRetriever("idx");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   ","named-index"})
    void blankIndexStillSearchesInjectedStoreAndContinues(String index) {
        var model=mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store=mock(EmbeddingStore.class);
        var embedding=Embedding.from(new float[]{1});
        when(model.embed(anyString())).thenReturn(Response.from(embedding));
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of(
            new EmbeddingMatch<>(.9,"fixture",embedding,TextSegment.from("vector evidence")))));
        var service=new LangChainRAGService(model,store);
        var handler=new VectorDbHandler(service,index,new OrchestrationGate(null));
        var results=new ArrayList<>(List.of(Content.from("web evidence")));
        handler.linkWith(new AbstractRetrievalHandler(){protected boolean doHandle(Query q,List<Content> acc){acc.add(Content.from("next evidence"));return false;}});
        handler.handle(new Query("synthetic"),results);
        assertEquals(List.of("web evidence","vector evidence","next evidence"),results.stream().map(c->c.textSegment().text()).toList());
        verify(store,times(1)).search(any(EmbeddingSearchRequest.class));
    }
    @Test void gateRejectionMakesZeroCalls() {
        var service=mock(LangChainRAGService.class);
        new VectorDbHandler(service,"",new OrchestrationGate(null)).handle(QueryUtils.buildQuery("synthetic",Map.of("allowRag",false)),new ArrayList<>());
        verifyNoInteractions(service);
    }
    @Test void missingRetrieverStillContinues() {
        var service=mock(LangChainRAGService.class);
        var handler=new VectorDbHandler(service,"",null);
        var results=new ArrayList<Content>();
        handler.linkWith(new AbstractRetrievalHandler(){protected boolean doHandle(Query q,List<Content> acc){acc.add(Content.from("next"));return false;}});
        handler.handle(new Query("synthetic"),results);
        assertEquals(1,results.size());verify(service).asContentRetriever("");
    }
}
