package com.example.lms.assist;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusHistoryTest {
    static AnnotationConfigApplicationContext context;
    static NovaFocusHistoryService store;
    @Configuration static class Database {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:nova_focus;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){
            var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);
            f.setPackagesToScan("com.example.lms");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.show_sql","false"));
            return f;
        }
        @Bean static PersistenceAnnotationBeanPostProcessor persistence(){return new PersistenceAnnotationBeanPostProcessor();}
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory emf){return new JpaTransactionManager(emf);}
        @Bean NovaFocusHistoryService store(JpaTransactionManager tx){var h=mock(ChatHistoryService.class);
            when(h.getRollingSummary(anyLong())).thenReturn(Optional.of("bounded summary"));
            return new NovaFocusHistoryService(tx,new ObjectMapper(),h);}
    }
    @BeforeAll static void start(){context=new AnnotationConfigApplicationContext(Database.class);store=context.getBean(NovaFocusHistoryService.class);}
    @AfterAll static void stop(){if(context!=null)context.close();}
    @Test void readsDoNotCreateRoomsAndSettingsUseCas(){
        String owner=UUID.randomUUID().toString();assertEquals(0,store.settings(owner,"c").settingsVersion());
        assertTrue(store.page(owner,"c",null,10).turns().isEmpty());
        var value=NovaFocusSettings.defaults();assertEquals(1,store.settings(owner,"c",0,value).settingsVersion());
        assertThrows(IllegalArgumentException.class,()->store.settings(owner,"c",0,value));
        assertEquals(1,store.settings(owner,"c").settingsVersion());
    }
    @Test void concurrentOpenCreatesOneRoomAndDifferentOwnersStaySeparate() throws Exception {
        String owner=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(4);
        try{var jobs=new ArrayList<Callable<Long>>();for(int i=0;i<8;i++)jobs.add(()->store.open(owner,"channel"));
            var ids=new HashSet<Long>();for(var f:pool.invokeAll(jobs))ids.add(f.get());
            assertEquals(1,ids.size());assertNotEquals(ids.iterator().next(),store.open(owner+"other","channel"));
        }finally{pool.shutdownNow();}
    }
    @Test void idempotencyAndTerminalCasDoNotDuplicateMessages() throws Exception {
        String owner=UUID.randomUUID().toString();var first=store.accept(owner,"c","a","r","question");
        assertTrue(first.created());var again=store.accept(owner,"c","a","r","question");assertFalse(again.created());assertEquals(first.turnId(),again.turnId());
        assertEquals(first.turnId(),store.accept(owner,"c","reconnected","r","question").turnId());
        assertThrows(IllegalArgumentException.class,()->store.accept(owner,"c","a","r","different"));
        assertTrue(store.terminal(owner,"c",first.turnId(),"COMPLETED","answer"));
        assertFalse(store.terminal(owner,"c",first.turnId(),"COMPLETED","duplicate"));
        assertFalse(store.terminal(owner,"c",first.turnId(),"CANCELLED",null));
        var page=store.page(owner,"c",null,10);assertEquals(1,page.turns().size());assertEquals("",page.turns().get(0).answer());
        var em=context.getBean(EntityManagerFactory.class).createEntityManager();
        try{assertEquals(0L,em.createQuery("select count(m) from ChatMessage m where m.session.id=:id",Long.class).setParameter("id",first.chatSessionId()).getSingleResult());}finally{em.close();}
    }
    @Test void boundedHistoryContextAndCrashRecovery(){
        String owner=UUID.randomUUID().toString();
        for(int i=1;i<=5;i++){var t=store.accept(owner,"c","a","r"+i,"topic "+i);store.terminal(owner,"c",t.turnId(),"COMPLETED","response "+i);}
        var pending=store.accept(owner,"c","a","pending","unknown result");store.recover(owner,"c");
        assertFalse(store.terminal(owner,"c",pending.turnId(),"COMPLETED","late"));
        var page=store.page(owner,"c",null,2);assertEquals(2,page.turns().size());assertNotNull(page.beforeSequence());
        assertEquals("OUTCOME_UNKNOWN",page.turns().get(0).state());
        assertEquals(4,store.page(owner,"c",page.beforeSequence(),30).turns().size());
        var memory=store.context(owner,"c","topic");assertTrue(memory.recent().isEmpty());
        assertTrue(memory.relevant().isEmpty());assertEquals("",memory.summary());
        assertTrue(store.page(owner+"stranger","c",null,30).turns().isEmpty());
    }
    @Test void multilingualMemoryUsesByteCeilingRatherThanEnglishCharacterRatio(){
        String text="한글👨‍👩‍👧‍👦é".repeat(500);
        String bounded=NovaFocusHistoryService.memoryClip(text,600);
        assertTrue(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=600);
        assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length()-1)));
    }
}
