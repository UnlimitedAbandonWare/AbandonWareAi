package com.example.lms.assist;

import com.example.lms.service.embedding.OllamaEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FocusMemoryContractTest {
    @Configuration @Import(NovaFocusHistoryTest.Database.class) static class Database {
        @Bean OllamaEmbeddingModel embedding(){var model=mock(OllamaEmbeddingModel.class);
            when(model.privateFingerprint()).thenReturn("ollama|synthetic|3");when(model.privateDimensions()).thenReturn(3);
            when(model.embedPrivate(anyString())).thenAnswer(a->{String s=a.getArgument(0);
                return Embedding.from(s.contains("outlet")?new float[]{0,0,1}:s.contains("supply")?new float[]{0,1,0}:new float[]{1,0,0});});return model;}
        @Bean FocusMemoryService memory(JpaTransactionManager tx,OllamaEmbeddingModel e){return new FocusMemoryService(tx,e);}
    }
    static AnnotationConfigApplicationContext context;
    static FocusMemoryService memory;static NovaFocusHistoryService history;
    @BeforeAll static void start(){context=new AnnotationConfigApplicationContext(Database.class);memory=context.getBean(FocusMemoryService.class);history=context.getBean(NovaFocusHistoryService.class);}
    @AfterAll static void stop(){context.close();}
    static NovaFocusSettings settings(boolean read,boolean write){return new NovaFocusSettings(true,"노바",1200,20000,8000,NovaFocusSettings.Presentation.defaults(),read,write);}
    String enabled(){String o=UUID.randomUUID().toString();history.settings(o,"c",0,settings(true,true));return o;}
    FocusMemoryService.Edit edit(String text,List<String> terms){return new FocusMemoryService.Edit(null,0,1,text,terms,"USER_REPORTED",null,true);}
    @Test void omittedConsentDefaultsOffAndSelectionIsRequired() throws Exception{
        var old=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"enabled\":true,\"wakeWord\":\"노바\",\"utteranceQuietMs\":1200,\"followupIdleMs\":20000,\"wakeListenTimeoutMs\":8000}",NovaFocusSettings.class);
        assertFalse(old.recallEnabled());assertFalse(old.rememberFactsEnabled());
        String owner=UUID.randomUUID().toString();history.settings(owner,"c",0,settings(true,false));
        assertThrows(IllegalArgumentException.class,()->memory.save(owner,"c",edit("gpu",List.of("3090"))));
        assertEquals(FocusMemoryService.Status.NO_AUTHORIZED_MEMORY,memory.retrieve(memory.scope(owner,"c"),"gpu",()->true).status());
        final String approved=enabled();
        assertThrows(IllegalArgumentException.class,()->memory.save(approved,"c",new FocusMemoryService.Edit(null,0,1,"gpu",List.of(),"VERIFIED",null,true)));
        assertThrows(IllegalArgumentException.class,()->memory.save(approved,"c",new FocusMemoryService.Edit(null,0,1,"gpu",List.of(),"USER_REPORTED",null,false)));
        assertTrue(memory.list(approved,"c").isEmpty());
    }
    @Test void legacyH2ProfileMigrationPreservesSettingsAndIsIdempotent(){
        String owner=enabled();
        var manager=context.getBean(jakarta.persistence.EntityManagerFactory.class).createEntityManager();
        try{
            manager.getTransaction().begin();
            manager.createNativeQuery("ALTER TABLE nova_focus_profile DROP COLUMN memory_revision").executeUpdate();
            manager.getTransaction().commit();
            history.ensureMemoryRevisionColumn();history.ensureMemoryRevisionColumn();
            assertEquals(1,history.settings(owner,"c").settingsVersion());
            assertTrue(history.settings(owner,"c").settings().rememberFactsEnabled());
            assertEquals(0,memory.scope(owner,"c").indexRevision());
        }finally{manager.close();}
    }
    @Test void semanticSeedsAndTwoHopExpansionNeverCrossOwners(){
        String a=enabled(),b=enabled();
        var first=memory.save(a,"c",edit("gpu fan incident",List.of("3090","power")));
        var second=memory.save(a,"c",edit("supply inspection",List.of("power","socket")));
        var third=memory.save(a,"c",edit("outlet observation",List.of("socket","room")));
        var alien=memory.save(b,"c",edit("gpu OTHER OWNER",List.of("3090","power")));
        var result=memory.retrieve(memory.scope(a,"c"),"예전 그래픽 장치 문제와 연결된 기록",()->true);
        assertEquals(FocusMemoryService.Status.OK,result.status(),result.degradationReason());assertEquals(1,result.vectorHits());assertEquals(2,result.graphHops());assertEquals(2,result.graphHits());
        var ids=result.evidence().stream().map(MemoryEvidence::sourceId).toList();
        assertTrue(ids.containsAll(List.of(first.sourceId(),second.sourceId(),third.sourceId())));assertFalse(ids.contains(alien.sourceId()));
        assertTrue(result.evidence().stream().allMatch(e->e.assertionType().equals("USER_REPORTED")));
        assertTrue(result.evidence().stream().allMatch(e->e.sourceRole().equals("USER")&&e.relationshipType().equals("CO_MENTIONED_WITH")));
        assertTrue(result.evidence().stream().anyMatch(e->e.entities().containsAll(List.of("power","socket"))));
        assertTrue(result.evidenceBytes()<=3072);
    }
    @Test void correctionDeletionAndRevocationInvalidateOldEvidence(){
        String a=enabled();var f=memory.save(a,"c",edit("gpu old report",List.of("3090")));
        var oldScope=memory.scope(a,"c");var old=memory.retrieve(oldScope,"gpu",()->true);
        var v2=memory.save(a,"c",new FocusMemoryService.Edit(f.sourceId(),f.revision(),1,"gpu corrected",List.of("3090"),"HYPOTHESIS",null,true));
        assertEquals(2,v2.revision());assertFalse(memory.valid(oldScope,old.evidence()));
        assertEquals("gpu corrected",memory.retrieve(memory.scope(a,"c"),"gpu",()->true).evidence().get(0).text());
        assertThrows(IllegalArgumentException.class,()->memory.save(a,"c",new FocusMemoryService.Edit(f.sourceId(),1,1,"late",List.of(),"USER_REPORTED",null,true)));
        memory.delete(a,"c",v2.sourceId(),2,1);assertTrue(memory.list(a,"c").isEmpty());
        assertThrows(IllegalArgumentException.class,()->memory.save(a,"c",new FocusMemoryService.Edit(f.sourceId(),2,1,"resurrect",List.of(),"USER_REPORTED",null,true)));
        memory.save(a,"c",edit("gpu new selected fact",List.of("3090")));
        var approved=memory.scope(a,"c");history.settings(a,"c",1,settings(false,false));
        assertFalse(memory.current(approved));assertEquals(FocusMemoryService.Status.OFF,memory.retrieve(memory.scope(a,"c"),"gpu",()->true).status());
        assertThrows(IllegalArgumentException.class,()->memory.save(a,"c",edit("queued before revoke",List.of())));
    }
    @Test void cancellationIsTerminalAndDimensionMismatchIsExplicit(){
        String a=enabled();memory.save(a,"c",edit("gpu fixture",List.of("3090")));
        assertThrows(CancellationException.class,()->memory.retrieve(memory.scope(a,"c"),"gpu",()->false));
        var fp=context.getBean(OllamaEmbeddingModel.class);when(fp.privateDimensions()).thenReturn(4);
        try{var result=memory.retrieve(memory.scope(a,"c"),"3090",()->true);
            assertEquals(FocusMemoryService.Status.DEGRADED,result.status());assertEquals(0,result.vectorHits());assertEquals(1,result.evidence().size());}
        finally{when(fp.privateDimensions()).thenReturn(3);}
    }
    @Test void unindexedFactsUseLocalGraphWithoutWaitingForAnUnavailableEmbedding(){
        var model=context.getBean(OllamaEmbeddingModel.class);
        when(model.embedPrivate("unindexed fixture")).thenThrow(new IllegalStateException("local_unavailable"));
        String owner=enabled();var fact=memory.save(owner,"c",edit("unindexed fixture",List.of("fixture")));
        assertFalse(fact.indexed());clearInvocations(model);
        var result=memory.retrieve(memory.scope(owner,"c"),"fixture",()->true);
        assertEquals(FocusMemoryService.Status.DEGRADED,result.status());
        assertEquals(fact.sourceId(),result.evidence().get(0).sourceId());
        verify(model,never()).embedPrivate(anyString());
    }
    @Test void saveRetriesAndIdenticalExternalIdsAreOwnerScoped(){
        String a=enabled(),b=enabled(),id=UUID.randomUUID().toString();
        var request=new FocusMemoryService.Edit(id,0,1,"gpu selected",List.of("3090"),"USER_REPORTED",null,true);
        var first=memory.save(a,"c",request);var scope=memory.scope(a,"c");
        assertEquals(first,memory.save(a,"c",request));assertEquals(scope,memory.scope(a,"c"));
        assertEquals(id,memory.save(b,"c",request).sourceId());assertEquals(1,memory.list(a,"c").size());assertEquals(1,memory.list(b,"c").size());
        memory.delete(a,"c",id,1,1);
        assertThrows(IllegalArgumentException.class,()->memory.save(a,"c",request));
        assertEquals(1,memory.list(b,"c").size());
    }
}
