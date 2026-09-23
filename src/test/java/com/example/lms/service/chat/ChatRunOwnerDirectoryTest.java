package com.example.lms.service.chat;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatRunOwnerDirectoryTest {
    JdbcTemplate db;
    ChatRunOwnerDirectory a, b;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:owners"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20260912_05__chat_run_owners.sql")).execute(ds);
        db = new JdbcTemplate(ds);
        a = new ChatRunOwnerDirectory(ds, "node-a"); b = new ChatRunOwnerDirectory(ds, "node-b");
    }
    @Test void fiveSimultaneousNodesClaimOnlyOneExecution() throws Exception {
        var executor=Executors.newFixedThreadPool(5); var start=new CountDownLatch(1); var claimed=new AtomicInteger();
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<5;i++){var node=i%2==0?a:b; futures.add(executor.submit(()->{
                start.await(); try { node.claim(10L, UUID.randomUUID().toString()); claimed.incrementAndGet(); }
                catch(ResponseStatusException busy){assertEquals(409,busy.getStatusCode().value());} return null;
            }));}
            start.countDown(); for(var f:futures)f.get(10,TimeUnit.SECONDS);
            assertEquals(1,claimed.get()); assertEquals(1,db.queryForObject("select count(*) from awx_chat_run_owners",Integer.class));
        } finally {executor.shutdownNow();}
    }
    @Test void expiredOwnerIsUnknownAndNeverAutomaticallyTakenOverOrRenewed() {
        String token=UUID.randomUUID().toString();a.claim(11L,token);
        db.update("update awx_chat_run_owners set lease_until=TIMESTAMP '2000-01-01 00:00:00' where run_token=?",token);
        assertFalse(a.renew(Set.of(token)).contains(token));
        var unavailable=assertThrows(ResponseStatusException.class,()->b.claim(11L,UUID.randomUUID().toString()));
        assertEquals(503,unavailable.getStatusCode().value());
        assertFalse(a.finish(11L,token,300));
        assertEquals(token,a.currentToken(11L).orElseThrow());
        assertEquals(1,db.queryForObject("select count(*) from awx_chat_run_owners",Integer.class));
    }
    @Test void completedReplaySurvivesNewRunAndOldOwnerCannotOverwriteIt() {
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();a.claim(12L,first);
        assertTrue(a.finish(12L,first,300)); b.claim(12L,second);
        assertEquals(first,b.find(12L,first).orElseThrow().runToken());
        assertFalse(a.finish(12L,first,300)); assertEquals(second,a.currentToken(12L).orElseThrow());
        assertTrue(a.find(999L,first).isEmpty());
        db.update("update awx_chat_run_owners set replay_until=TIMESTAMP '2000-01-01 00:00:00' where run_token=?",first);
        a.prune(); assertTrue(a.find(12L,first).isEmpty());assertTrue(a.find(12L,second).isPresent());
    }
    @Test void sameInstanceNameAfterRestartIsNotThePreviousOwner() {
        String token=UUID.randomUUID().toString();a.claim(13L,token);
        var restarted=new ChatRunOwnerDirectory(db.getDataSource(),"node-a");
        assertFalse(restarted.isLocal(a.find(13L,token).orElseThrow()));
        assertFalse(restarted.renew(Set.of(token)).contains(token));
        assertFalse(restarted.finish(13L,token,300));
    }
    @Test void directoryContainsOnlyRoutingMetadata() {
        a.claim(14L,UUID.randomUUID().toString());
        var columns=db.queryForList("select * from awx_chat_run_owners").get(0).keySet();
        assertEquals(Set.of("RUN_TOKEN","SESSION_ID","INSTANCE_ID","BOOT_ID","STATE","LEASE_UNTIL","REPLAY_UNTIL"),columns);
    }
}
