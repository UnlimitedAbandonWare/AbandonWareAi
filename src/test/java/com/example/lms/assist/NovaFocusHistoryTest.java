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
    @Test void unsetDisplayDefaultIsExactOauthAndExplicitChoicesAndOmittedLengthArePreserved() throws Exception {
        String owner=UUID.randomUUID().toString();var fresh=store.settings(owner,"c");
        assertEquals("chatgpt-oauth:gpt-5.6-luna",fresh.settings().answerSelection().modelId());
        assertFalse(fresh.settings().answerSelection().routing().fallbackAllowed());
        var mapper=new ObjectMapper();var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(fresh.settings());
        node.put("answerLengthChars",480);node.put("quickAnswerEnabled",true);
        node.set("answerSelection",mapper.valueToTree(new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"fixture-model-b")));
        var chosen=mapper.treeToValue(node,NovaFocusSettings.class);store.settings(owner,"c",0,chosen);
        node.remove(List.of("answerLengthChars","quickAnswerEnabled","answerSelection"));
        var legacy=mapper.treeToValue(node,NovaFocusSettings.class);var saved=store.settings(owner,"c",1,legacy).settings();
        assertEquals(480,saved.effectiveAnswerLengthChars());assertTrue(saved.quickAnswer());assertEquals("fixture-model-b",saved.answerSelection().modelId());
        assertEquals("fixture-model-b",store.settings(owner,"c").settings().answerSelection().modelId());
        assertEquals(400,store.settings(owner,"other").settings().effectiveAnswerLengthChars());
        node.set("answerSelection",mapper.valueToTree(NovaFocusSettings.AnswerSelection.defaults()));
        store.settings(owner,"c",2,mapper.treeToValue(node,NovaFocusSettings.class));
        assertEquals(NovaFocusSettings.AnswerSelection.Mode.AUTO,store.settings(owner,"c").settings().answerSelection().mode());
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
    @Test void snapshotSettingsSurviveLegacyPayloadSavesAndOldJson() throws Exception {
        String owner=UUID.randomUUID().toString();var d=NovaFocusSettings.defaults();
        var withSnap=new NovaFocusSettings(d.enabled(),d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation(),d.recallEnabled(),d.rememberFactsEnabled(),new NovaFocusSettings.Snapshot(true,"META_GLASSES"));
        assertEquals(1,store.settings(owner,"snap",0,withSnap).settingsVersion());
        var legacy=new NovaFocusSettings(true,"노바",1200,20000,8000,NovaFocusSettings.Presentation.defaults(),false,false);
        assertNull(legacy.snapshot());
        assertEquals(2,store.settings(owner,"snap",1,legacy).settingsVersion());
        var read=store.settings(owner,"snap").settings();
        assertNotNull(read.snapshot());assertTrue(read.snapshot().enabled());assertEquals("META_GLASSES",read.snapshot().source());
        var old=new ObjectMapper().readValue("{\"enabled\":true,\"wakeWord\":\"노바\",\"utteranceQuietMs\":1200,\"followupIdleMs\":20000,\"wakeListenTimeoutMs\":8000}",NovaFocusSettings.class);
        assertNull(old.snapshot());assertFalse(old.snapshotOrDefault().enabled());
    }
    @Test void multilingualMemoryUsesByteCeilingRatherThanEnglishCharacterRatio(){
        String text="한글👨‍👩‍👧‍👦é".repeat(500);
        String bounded=NovaFocusHistoryService.memoryClip(text,600);
        assertTrue(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=600);
        assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length()-1)));
    }
    @Test void answerSelectionSurvivesLegacySaveAndExplicitAutoClearsFixedChoice() throws Exception {
        String owner=UUID.randomUUID().toString();var d=NovaFocusSettings.defaults();
        var fixed=new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"fixture-model-a");
        var selected=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),
            d.presentation(),false,false,d.snapshot(),fixed);
        store.settings(owner,"selected",0,selected);
        var legacy=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),
            d.presentation(),false,false,new NovaFocusSettings.Snapshot(true,"FOLD_REAR"));
        var preserved=store.settings(owner,"selected",1,legacy).settings();
        assertEquals(fixed,preserved.answerSelection());assertTrue(preserved.snapshot().enabled());
        var automatic=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),
            d.presentation(),false,false,null,NovaFocusSettings.AnswerSelection.defaults());
        var cleared=store.settings(owner,"selected",2,automatic).settings();
        assertEquals(NovaFocusSettings.AnswerSelection.Mode.AUTO,cleared.answerSelection().mode());
        assertNull(cleared.answerSelection().modelId());assertTrue(cleared.snapshot().enabled());
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,null));
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"bad\nmodel"));
    }
    @Test void legacyAnswerSelectionPreservesRoutingUntilAnExplicitReplacement(){
        String owner=UUID.randomUUID().toString();var d=NovaFocusSettings.defaults();
        var route=new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.API_ONLY,true,List.of("llmrouter.backup"));
        var selected=new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"llmrouter.primary",route);
        var initial=new NovaFocusSettings(true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false,d.snapshot(),selected);
        store.settings(owner,"routing",0,initial);
        var legacyChoice=new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"llmrouter.next");
        var legacy=new NovaFocusSettings(true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false,d.snapshot(),legacyChoice);
        var preserved=store.settings(owner,"routing",1,legacy).settings().answerSelection();
        assertEquals("llmrouter.next",preserved.modelId());assertEquals(route,preserved.routing());
        var openRoute=new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of());
        var explicit=new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.AUTO,null,openRoute);
        var cleared=store.settings(owner,"routing",2,new NovaFocusSettings(true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false,d.snapshot(),explicit));
        assertEquals(openRoute,cleared.settings().answerSelection().routing());
    }
    @Test void omittedRecentSettingsPreserveStoredPolicyAndExplicitValuesReplaceIt(){
        String owner=UUID.randomUUID().toString();var d=NovaFocusSettings.defaults();
        var custom=new NovaFocusSettings.RecentContext(false,60,3,800);
        var initial=new NovaFocusSettings(true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false,d.snapshot(),d.answerSelection(),custom);
        store.settings(owner,"recent-settings",0,initial);
        var legacy=new NovaFocusSettings(true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false,d.snapshot(),d.answerSelection());
        assertEquals(custom,store.settings(owner,"recent-settings",1,legacy).settings().recentContext());
        var explicit=new NovaFocusSettings(true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false,d.snapshot(),d.answerSelection(),NovaFocusSettings.RecentContext.defaults());
        assertEquals(NovaFocusSettings.RecentContext.defaults(),store.settings(owner,"recent-settings",2,explicit).settings().recentContext());
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings.RecentContext(true,180,12,2001));
    }
    @Test void omittedMemoryPreservesStoredPolicyAndExplicitBlockReplacesIt(){
        String owner=UUID.randomUUID().toString();var d=NovaFocusSettings.defaults();
        var custom=new NovaFocusSettings.Memory(NovaFocusSettings.Memory.Mode.FULL,NovaFocusSettings.Memory.GraphMode.AUTO,6,NovaFocusSettings.Memory.EmbeddingPrefer.LOCAL_ONLY,false);
        var initial=new NovaFocusSettings(true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false,d.snapshot(),d.answerSelection(),d.recentContext(),custom);
        assertEquals(1,store.settings(owner,"mem",0,initial).settingsVersion());
        // 예전 클라이언트(11필드, memory 생략)가 덮어도 저장된 메모리 정책은 유지된다.
        var legacy=new NovaFocusSettings(true,d.wakeWord(),1500,25000,9000,d.presentation(),true,false,d.snapshot(),d.answerSelection());
        var kept=store.settings(owner,"mem",1,legacy).settings();
        assertEquals(custom,kept.memory());assertEquals(1500,kept.utteranceQuietMs());assertTrue(kept.recallEnabled());
        // 명시된 블록은 완전히 새 값으로 교체한다 — 부분 병합이 아니다.
        var cleared=store.settings(owner,"mem",2,new NovaFocusSettings(true,d.wakeWord(),1500,25000,9000,d.presentation(),true,false,d.snapshot(),d.answerSelection(),null,new NovaFocusSettings.Memory(null,null,null,null,null))).settings().memory();
        assertEquals(NovaFocusSettings.Memory.GraphMode.OFF,cleared.graphMode());assertEquals(4,cleared.maxEvidence().intValue());
        assertNull(cleared.mode());assertNull(cleared.webOnUnknown());
        assertEquals(NovaFocusSettings.Memory.EmbeddingPrefer.LOCAL_THEN_CLOUD,cleared.embeddingPrefer());
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings.Memory(null,null,9,null,null));
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings.Memory(null,null,0,null,null));
        // CAS 동작은 그대로다.
        assertThrows(IllegalArgumentException.class,()->store.settings(owner,"mem",2,legacy));
    }
}
