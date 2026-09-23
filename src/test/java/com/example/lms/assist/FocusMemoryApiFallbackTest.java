package com.example.lms.assist;

import com.example.lms.config.FocusMemoryEmbeddingConfig;
import com.example.lms.service.embedding.OllamaEmbeddingModel;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** CPU-only HTTP fixture; never calls Ollama or an external provider. */
class FocusMemoryApiFallbackTest {
    @Configuration @Import({NovaFocusHistoryTest.Database.class,FocusMemoryEmbeddingConfig.class})
    static class Database {
        @Bean OllamaEmbeddingModel local(){var m=mock(OllamaEmbeddingModel.class);
            when(m.privateFingerprint()).thenReturn("ollama|fixture|3");when(m.privateDimensions()).thenReturn(3);
            when(m.embedPrivate(anyString())).thenThrow(new IllegalStateException("local_timeout"));return m;}
        @Bean FocusMemoryService memory(JpaTransactionManager tx,OllamaEmbeddingModel local){return new FocusMemoryService(tx,local);}
    }
    HttpServer server;AnnotationConfigApplicationContext context;FocusMemoryService memory;NovaFocusHistoryService history;
    AtomicInteger requests=new AtomicInteger();volatile int responseStatus=200;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",exchange->{
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] body=(responseStatus==200?"{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]}],\"model\":\"text-embedding-3-small\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}":"{\"error\":{\"message\":\"fixture unavailable\",\"type\":\"server_error\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(responseStatus,body.length);
            exchange.getResponseBody().write(body);exchange.close();});server.start();
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of(
            "embedding.fallback.api-key","synthetic-fixture-credential", "embedding.fallback.base-url","http://127.0.0.1:"+server.getAddress().getPort(),
            "embedding.fallback.dimensions","3","embedding.fallback.enabled","false","focus.memory.embedding.local-enabled","false")));
        context.register(Database.class);context.refresh();memory=context.getBean(FocusMemoryService.class);history=context.getBean(NovaFocusHistoryService.class);
    }
    @AfterEach void stop(){if(context!=null)context.close();if(server!=null)server.stop(0);}
    String owner(){String id=UUID.randomUUID().toString();history.settings(id,"c",0,FocusMemoryContractTest.settings(true,true));return id;}
    FocusMemoryService.Fact save(String owner){return memory.save(owner,"c",new FocusMemoryService.Edit(null,0,1,"synthetic selected fact",List.of("unused-lexical-term"),"USER_REPORTED",null,true));}
    @Test void apiPreferredIndexesAndSearchesWithoutTouchingLocalOrOtherOwners(){
        String a=owner(),b=owner();var own=save(a);var foreign=save(b);assertTrue(own.indexed());assertTrue(foreign.indexed());
        var result=memory.retrieve(memory.scope(a,"c"),"different wording",()->true);
        assertEquals(FocusMemoryService.Status.OK,result.status());assertEquals(1,result.vectorHits());
        assertEquals(List.of(own.sourceId()),result.evidence().stream().map(MemoryEvidence::sourceId).toList());
        verify(context.getBean(OllamaEmbeddingModel.class),never()).embedPrivate(anyString());assertEquals(3,requests.get());
    }
    @Test void localFailureFallsBackOnceAndCooldownAvoidsRepeatedLocalAttempts(){
        ReflectionTestUtils.setField(memory,"localEnabled",true);String a=owner();assertTrue(save(a).indexed());
        assertEquals(1,memory.retrieve(memory.scope(a,"c"),"different wording",()->true).vectorHits());
        assertTrue(save(owner()).indexed());verify(context.getBean(OllamaEmbeddingModel.class),times(1)).embedPrivate(anyString());
        assertEquals(3,requests.get());
    }
    @Test void cloudFailureDoesNotRetryOrQueryUnindexedFacts(){
        responseStatus=503;String a=owner();assertFalse(save(a).indexed());assertEquals(1,requests.get());
        assertEquals(0,memory.retrieve(memory.scope(a,"c"),"different wording",()->true).vectorHits());assertEquals(1,requests.get());
        verify(context.getBean(OllamaEmbeddingModel.class),never()).embedPrivate(anyString());
    }
    @Test void localVectorsAreNotComparedWithCloudVectors(){
        ReflectionTestUtils.setField(memory,"localEnabled",true);
        doReturn(Embedding.from(new float[]{1,0,0})).when(context.getBean(OllamaEmbeddingModel.class)).embedPrivate(anyString());
        String a=owner();assertTrue(save(a).indexed());ReflectionTestUtils.setField(memory,"localEnabled",false);
        var result=memory.retrieve(memory.scope(a,"c"),"different wording",()->true);
        assertEquals(0,result.vectorHits());assertEquals(0,requests.get());assertEquals("no_compatible_embeddings",result.degradationReason());
    }
    @Test void missingOrPlaceholderCredentialsDisableTheCloudBean(){
        try(var disabled=new AnnotationConfigApplicationContext()){
            disabled.getEnvironment().getPropertySources().addFirst(new MapPropertySource("missing",Map.of(
                "embedding.fallback.api-key","test","openai.api.key","test","OPENAI_API_KEY","test")));
            disabled.register(FocusMemoryEmbeddingConfig.class);disabled.refresh();
            assertTrue(disabled.getBeansOfType(FocusMemoryEmbeddingConfig.FocusCloudEmbedding.class).isEmpty());
        }
        assertEquals(0,requests.get());
    }
    @Test void focusCloudCanBeDisabledWithoutChangingGeneralFallback(){
        try(var disabled=new AnnotationConfigApplicationContext()){
            disabled.getEnvironment().getPropertySources().addFirst(new MapPropertySource("disabled",Map.of(
                "embedding.fallback.api-key","synthetic-fixture-credential","embedding.fallback.enabled","true",
                "focus.memory.embedding.cloud-enabled","false")));
            disabled.register(FocusMemoryEmbeddingConfig.class);disabled.refresh();
            assertTrue(disabled.getBeansOfType(FocusMemoryEmbeddingConfig.FocusCloudEmbedding.class).isEmpty());
        }
        assertEquals(0,requests.get());
    }
}
