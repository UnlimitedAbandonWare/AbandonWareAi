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
    @Test void oldWakeMatchingNewDefaultExitGetsVisibleCompatibleExitWithoutChangingUserQuiet() throws Exception {
        var mapper=new ObjectMapper();String owner=UUID.randomUUID().toString();
        for(String field:List.of("wakeWord","cameraWakeWord")){
            String channel="legacy-"+field;var initial=store.settings(owner,channel).settings();
            store.settings(owner,channel,0,initial);
            var raw=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(initial);
            raw.remove("exitWord");raw.put(field,"클린");raw.put("utteranceQuietMs",1234);
            var em=context.getBean(EntityManagerFactory.class).createEntityManager();
            try{em.getTransaction().begin();em.find(NovaFocusProfile.class,NovaFocusHistoryService.scope(owner,channel)).setSettingsJson(mapper.writeValueAsString(raw));em.getTransaction().commit();}
            finally{em.close();}
            var read=store.settings(owner,channel).settings();assertEquals(1234,read.utteranceQuietMs());
            assertEquals("포커스 종료",read.exitWord());assertEquals("클린",field.equals("wakeWord")?read.wakeWord():read.cameraWakeWord());
            assertDoesNotThrow(()->store.settings(owner,channel,1,read));
            raw.put("exitWord","클린");assertThrows(Exception.class,()->mapper.treeToValue(raw,NovaFocusSettings.class));
        }
    }
    @Test void exitWordSurvivesSaveReopenLegacyMergeAndOwnerIsolation() throws Exception {
        String owner=UUID.randomUUID().toString();var mapper=new ObjectMapper();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(store.settings(owner,"exit-word").settings());
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("snapshot")).put("cameraAllowed",false);
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("answerSelection")).set("routing",mapper.valueToTree(new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of())));
        node.put("cameraWakeWord","camera").put("answerInstruction","").put("answerPreset","GENERAL").put("reasoningPreset","STANDARD").put("webSearchEnabled",true);
        node.put("exitWord","finish");store.settings(owner,"exit-word",0,mapper.treeToValue(node,NovaFocusSettings.class));
        assertEquals("finish",store.settings(owner,"exit-word").settings().exitWordOrDefault());
        node.remove("exitWord");store.settings(owner,"exit-word",1,mapper.treeToValue(node,NovaFocusSettings.class));
        var restarted=new NovaFocusHistoryService(context.getBean(org.springframework.transaction.PlatformTransactionManager.class),mapper,null);
        org.springframework.test.util.ReflectionTestUtils.setField(restarted,"em",SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class)));
        assertEquals("finish",restarted.settings(owner,"exit-word").settings().exitWordOrDefault());
        assertEquals("클린",restarted.settings("other-"+owner,"exit-word").settings().exitWordOrDefault());
        node.put("exitWord",node.path("wakeWord").asText());assertThrows(Exception.class,()->mapper.treeToValue(node,NovaFocusSettings.class));
    }
    @Test void dedicatedModeAndFallbackPreferencesSurviveLegacySaveCasReconnectAndOwnerIsolation() throws Exception {
        String owner=UUID.randomUUID().toString();var mapper=new ObjectMapper();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(store.settings(owner,"dedicated").settings());
        var selection=node.putObject("answerSelection").put("mode","FIXED").put("modelId","llmrouter.gemini-pro");
        selection.putObject("routing").put("executionTarget","GEMINI_WEBSEARCH_ONLY").put("fallbackAllowed",true).putArray("allowedFallbackIds").add("llmrouter.backup");
        node.put("webSearchEnabled",false);store.settings(owner,"dedicated",0,mapper.treeToValue(node,NovaFocusSettings.class));
        node.remove("answerSelection");node.remove("webSearchEnabled");store.settings(owner,"dedicated",1,mapper.treeToValue(node,NovaFocusSettings.class));
        var saved=store.settings(owner,"dedicated");assertEquals(2,saved.settingsVersion());
        assertEquals("GEMINI_WEBSEARCH_ONLY",saved.settings().answerSelection().routing().executionTarget().name());
        assertEquals(List.of("llmrouter.backup"),saved.settings().answerSelection().routing().allowedFallbackIds());assertTrue(saved.settings().answerSelection().routing().fallbackAllowed());
        assertFalse(saved.settings().webSearchAllowed());assertThrows(IllegalArgumentException.class,()->store.settings(owner,"dedicated",1,saved.settings()));
        assertNotEquals(saved.settings().answerSelection(),store.settings(owner+"other","dedicated").settings().answerSelection());
        assertNotEquals(saved.settings().answerSelection(),store.settings(owner,"other-channel").settings().answerSelection());
    }
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
    @Test void newSavesRejectEquivalentWakeWordsWhileLegacyDecodeAndExplicitOffRemainCompatible() throws Exception {
        var mapper=new ObjectMapper();var d=NovaFocusSettings.defaults();
        for(String[] pair:new String[][]{{"NOVA","nova"},{"노바",java.text.Normalizer.normalize("노바",java.text.Normalizer.Form.NFD)},{"데빈",null}}){
            var legacy=new NovaFocusSettings(true,pair[0],1200,20000,8000,d.presentation(),false,false,null,pair[1]);
            assertDoesNotThrow(()->mapper.readValue(mapper.writeValueAsString(legacy),NovaFocusSettings.class));
            String owner=UUID.randomUUID().toString();
            assertThrows(IllegalArgumentException.class,()->store.settings(owner,"wake-equality",0,legacy));
            assertEquals(0,store.settings(owner,"wake-equality").settingsVersion());
        }
        var off=new NovaFocusSettings(true,"데빈",1200,20000,8000,d.presentation(),false,false,null,"");
        assertEquals("",store.settings(UUID.randomUUID().toString(),"wake-off",0,off).settings().cameraWakeWordOrDefault());
    }
    @Test void answerInstructionAndPresetRoundTripAndSurviveLegacyPayloads(){
        String owner=UUID.randomUUID().toString();var mapper=new ObjectMapper();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(store.settings(owner,"instruction").settings());
        node.put("answerInstruction","짧은 존댓말 답변.");node.put("answerPreset","INTERVIEW");
        var chosen=assertDoesNotThrow(()->mapper.treeToValue(node,NovaFocusSettings.class));
        store.settings(owner,"instruction",0,chosen);
        var saved=store.settings(owner,"instruction").settings();
        assertEquals("짧은 존댓말 답변.",saved.answerInstruction());
        assertEquals(NovaFocusSettings.AnswerPreset.INTERVIEW,saved.answerPreset());
        assertEquals("짧은 존댓말 답변.",saved.effectiveAnswerInstruction());
        node.remove("answerInstruction");node.remove("answerPreset");
        var legacy=assertDoesNotThrow(()->mapper.treeToValue(node,NovaFocusSettings.class));
        store.settings(owner,"instruction",1,legacy);
        var merged=store.settings(owner,"instruction").settings();
        assertEquals("짧은 존댓말 답변.",merged.answerInstruction());
        assertEquals(NovaFocusSettings.AnswerPreset.INTERVIEW,merged.answerPreset());
        var other=store.settings(owner+"other","instruction").settings();
        assertEquals(NovaFocusSettings.AnswerPreset.GENERAL,other.effectiveAnswerPreset());
        assertEquals("",other.effectiveAnswerInstruction());
    }
    @Test void readsDoNotCreateRoomsAndSettingsUseCas(){
        String owner=UUID.randomUUID().toString();assertEquals(0,store.settings(owner,"c").settingsVersion());
        assertTrue(store.page(owner,"c",null,10).turns().isEmpty());
        var value=NovaFocusSettings.defaults();assertEquals(1,store.settings(owner,"c",0,value).settingsVersion());
        assertThrows(IllegalArgumentException.class,()->store.settings(owner,"c",0,value));
        assertEquals(1,store.settings(owner,"c").settingsVersion());
    }
    @Test void searchFalseSurvivesLegacySaveReconnectCasAndOwnerIsolation() throws Exception {
        String owner=UUID.randomUUID().toString();var mapper=new ObjectMapper();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(store.settings(owner,"c").settings());
        node.put("webSearchEnabled",false);
        store.settings(owner,"c",0,mapper.treeToValue(node,NovaFocusSettings.class));
        node.remove("webSearchEnabled");
        store.settings(owner,"c",1,mapper.treeToValue(node,NovaFocusSettings.class));
        assertFalse(mapper.valueToTree(store.settings(owner,"c").settings()).path("webSearchEnabled").booleanValue());
        assertTrue(mapper.valueToTree(store.settings(owner,"c").settings()).hasNonNull("webSearchEnabled"));
        assertTrue(mapper.valueToTree(store.settings(owner+"other","c").settings()).path("webSearchEnabled").isNull());
        node.put("webSearchEnabled",true);
        assertThrows(IllegalArgumentException.class,()->store.settings(owner,"c",1,mapper.treeToValue(node,NovaFocusSettings.class)));
        store.settings(owner,"c",2,mapper.treeToValue(node,NovaFocusSettings.class));
        assertTrue(mapper.valueToTree(store.settings(owner,"c").settings()).path("webSearchEnabled").booleanValue());
    }
    @Test void unsetDisplayDefaultIsAutoWithApiFallbackAndExplicitChoicesArePreserved() throws Exception {
        String owner=UUID.randomUUID().toString();var fresh=store.settings(owner,"c");
        assertEquals(NovaFocusSettings.AnswerSelection.Mode.AUTO,fresh.settings().answerSelection().mode());
        assertNull(fresh.settings().answerSelection().modelId());
        assertEquals(NovaFocusSettings.ExecutionTarget.AUTO,fresh.settings().answerSelection().routing().executionTarget());
        assertTrue(fresh.settings().answerSelection().routing().fallbackAllowed());
        assertEquals(List.of("llmrouter.api3"),fresh.settings().answerSelection().routing().allowedFallbackIds());
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
    @Test void storedOldDefaultsMigrateOnReadAndDeliberateChoicesSurvive() {
        String owner=UUID.randomUUID().toString();var d=NovaFocusSettings.defaults();
        var legacySelection=new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"llmrouter.gemini-pro",
            new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of()));
        var stored=new NovaFocusSettings(true,"노바",1200,20000,8000,d.presentation(),false,false,d.snapshot(),legacySelection,d.recentContext(),d.memory(),400,false);
        store.settings(owner,"walk-mig",0,stored);
        var migrated=store.settings(owner,"walk-mig").settings();
        assertEquals(12000,migrated.wakeListenTimeoutMs());
        assertEquals(NovaFocusSettings.AnswerSelection.Mode.AUTO,migrated.answerSelection().mode());
        assertEquals(List.of("llmrouter.api3"),migrated.answerSelection().routing().allowedFallbackIds());
        assertTrue(migrated.answerSelection().routing().fallbackAllowed());
        String chosen=UUID.randomUUID().toString();
        var deliberate=new NovaFocusSettings(true,"노바",1200,20000,8000,d.presentation(),false,false,d.snapshot(),
            new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"llmrouter.openai-economy",
                new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.API_ONLY,false,List.of())),
            d.recentContext(),d.memory(),200,false,false);
        store.settings(chosen,"walk-mig",0,deliberate);
        var kept=store.settings(chosen,"walk-mig").settings();
        assertEquals(12000,kept.wakeListenTimeoutMs());
        assertEquals("llmrouter.openai-economy",kept.answerSelection().modelId());
        assertEquals(NovaFocusSettings.ExecutionTarget.API_ONLY,kept.answerSelection().routing().executionTarget());
        assertFalse(kept.answerSelection().routing().fallbackAllowed());
        assertEquals(200,kept.answerLengthChars().intValue());
        assertFalse(kept.webSearchAllowed());
    }
    @Test void reasoningIsIndependentPersistedAndLegacySavePreservesIt() throws Exception {
        String owner=UUID.randomUUID().toString();var mapper=new ObjectMapper();
        var fresh=store.settings(owner,"c");
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(fresh.settings());
        node.put("reasoningPreset","DEEP");
        var selected=mapper.treeToValue(node,NovaFocusSettings.class);
        store.settings(owner,"c",0,selected);
        node.remove("reasoningPreset");
        store.settings(owner,"c",1,mapper.treeToValue(node,NovaFocusSettings.class));
        var stored=mapper.valueToTree(store.settings(owner,"c").settings());
        assertEquals("DEEP",stored.path("reasoningPreset").asText());
        assertEquals(fresh.settings().answerSelection().modelId(),store.settings(owner,"c").settings().answerSelection().modelId());
        var restarted=new NovaFocusHistoryService(context.getBean(org.springframework.transaction.PlatformTransactionManager.class),mapper,null);
        var em=SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        org.springframework.test.util.ReflectionTestUtils.setField(restarted,"em",em);
        assertEquals("DEEP",mapper.valueToTree(restarted.settings(owner,"c").settings()).path("reasoningPreset").asText());
        assertThrows(Exception.class,()->mapper.treeToValue(node.deepCopy().put("reasoningPreset","TURBO"),NovaFocusSettings.class));
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
