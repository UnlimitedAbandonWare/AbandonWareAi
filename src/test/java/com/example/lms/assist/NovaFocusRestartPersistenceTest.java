package com.example.lms.assist;

import com.example.lms.service.ChatHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusRestartPersistenceTest {
    @TempDir Path temporary;
    record Database(LocalContainerEntityManagerFactoryBean factory,NovaFocusHistoryService history) implements AutoCloseable {
        public void close(){factory.destroy();}
    }
    Database open(String url){
        var source=new DriverManagerDataSource(url,"sa","");
        var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(source);
        factory.setPackagesToScan("com.example.lms");factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","update","hibernate.show_sql","false"));
        factory.afterPropertiesSet();
        var existingHistory=mock(ChatHistoryService.class);when(existingHistory.getRollingSummary(anyLong())).thenReturn(Optional.empty());
        var store=new NovaFocusHistoryService(new JpaTransactionManager(factory.getObject()),new ObjectMapper(),existingHistory);
        ReflectionTestUtils.setField(store,"em",SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        return new Database(factory,store);
    }
    @Test void completedHistorySettingsAndRequestIdentitySurviveDatabaseReopen(){
        String url="jdbc:h2:file:"+temporary.resolve("nova").toAbsolutePath().toString().replace('\\','/')+";MODE=MariaDB;DATABASE_TO_UPPER=false";
        String owner="synthetic-owner";Long room;String turn;
        try(var first=open(url)){
            var store=first.history();store.settings(owner,"live",0,NovaFocusSettings.defaults());room=store.open(owner,"live");
            var accepted=store.accept(owner,"live","activation-one",NovaFocusState.typedRequestId("request-one"),"이름은 무엇인가요?");turn=accepted.turnId();
            assertTrue(store.terminal(owner,"live",turn,"COMPLETED","별빛입니다."));
            store.accept(owner,"live","activation-one","pending","완료 여부가 불명확한 질문");
        }
        try(var second=open(url)){
            var store=second.history();store.recover(owner,"live");assertEquals(room,store.open(owner,"live"));
            assertEquals(1,store.settings(owner,"live").settingsVersion());
            var page=store.page(owner,"live",null,10);assertEquals(2,page.turns().size());
            assertEquals("OUTCOME_UNKNOWN",page.turns().get(0).state());assertEquals("",page.turns().get(1).answer());
            String key=NovaFocusState.typedRequestId("request-one");
            assertTrue(store.knownRequest(owner,"live",key,"이름은 무엇인가요?"));
            assertFalse(store.knownRequest("other-owner","live",key,"이름은 무엇인가요?"));
            assertFalse(store.knownRequest(owner,"live",NovaFocusState.typedRequestId("new-request"),"이름은 무엇인가요?"));
            var conflict=assertThrows(IllegalArgumentException.class,()->store.knownRequest(owner,"live",key,"다른 질문"));
            assertEquals("focus_request_conflict",conflict.getMessage());
            var replay=store.accept(owner,"live","activation-two",key,"이름은 무엇인가요?");
            assertFalse(replay.created());assertEquals(turn,replay.turnId());
            assertTrue(store.page("other-owner","live",null,10).turns().isEmpty());
        }
    }
}
