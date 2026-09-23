package com.example.lms.assist;

import com.example.lms.service.ChatHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusEphemeralPersistenceTest {
    static AnnotationConfigApplicationContext db;
    static NovaFocusHistoryService store;
    @BeforeAll static void start(){db=new AnnotationConfigApplicationContext(NovaFocusHistoryTest.Database.class);store=db.getBean(NovaFocusHistoryService.class);}
    @AfterAll static void stop(){if(db!=null)db.close();}
    @Test void defaultAcceptanceAndCompletionKeepNoMessageBodies(){
        String owner=UUID.randomUUID().toString();
        var turn=store.accept(owner,"private","activation","request","synthetic private question");
        assertTrue(store.terminal(owner,"private",turn.turnId(),"COMPLETED","synthetic private answer"));
        var em=db.getBean(EntityManagerFactory.class).createEntityManager();
        try{
            assertEquals(0L,em.createQuery("select count(m) from ChatMessage m where m.session.id=:id",Long.class)
                .setParameter("id",turn.chatSessionId()).getSingleResult());
        }finally{em.close();}
        var page=store.page(owner,"private",null,10);
        assertEquals(1,page.turns().size());
        assertEquals("",page.turns().get(0).question());assertEquals("",page.turns().get(0).answer());
        assertTrue(store.context(owner,"private","question").recent().isEmpty());
    }
    @Test void rawlessMetadataStillFencesDuplicateCancelAndRecovery(){
        String owner=UUID.randomUUID().toString();
        var a=store.accept(owner,"c","a","r","fixture");
        assertFalse(store.accept(owner,"c","a","r","fixture").created());
        assertThrows(IllegalArgumentException.class,()->store.accept(owner,"c","a","r","changed"));
        store.recover(owner,"c");
        assertFalse(store.terminal(owner,"c",a.turnId(),"COMPLETED","late"));
        assertEquals("OUTCOME_UNKNOWN",store.page(owner,"c",null,10).turns().get(0).state());
        assertTrue(store.page(owner+"other","c",null,10).turns().isEmpty());
    }
}

