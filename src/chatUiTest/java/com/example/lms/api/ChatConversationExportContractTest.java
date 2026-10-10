package com.example.lms.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.debug.*;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatConversationExportContractTest {
    @Test void renderedHeaderPreservesPreparedCurrentModel() throws Exception {
        var config=new com.example.lms.config.ChatUiViewConfig();
        var view=config.chatUiResourceViewResolver().resolveViewName("chat-ui",java.util.Locale.KOREA);
        var model=new com.example.lms.entity.ModelEntity();model.setModelId("qwen3:30b");model.setOwner("test");
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        view.render(Map.of("models",List.of(model),"currentModel","qwen3:30b"),new org.springframework.mock.web.MockHttpServletRequest("GET","/chat"),response);
        var element=org.jsoup.Jsoup.parse(response.getContentAsString()).selectFirst("[data-current-model]");
        assertNotNull(element);assertEquals("qwen3:30b",element.text());
        assertTrue(response.getContentAsString().contains("<strong data-current-model>qwen3:30b</strong>"),element.outerHtml());
    }
    final ObjectMapper mapper = new ObjectMapper();
    DriverManagerDataSource ds;
    JdbcTemplate sql;
    MutableClock clock;
    ChatConversationExportSupport export;
    final ChatConversationExportSupport.Actor alice = new ChatConversationExportSupport.Actor(null, "browser-alice");
    @BeforeEach void setup() {
        ds = new DriverManagerDataSource(); ds.setDriverClassName("org.h2.Driver"); ds.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        sql = new JdbcTemplate(ds); clock = new MutableClock();
        sql.execute("CREATE TABLE administrators(id BIGINT PRIMARY KEY, username VARCHAR(50))");
        sql.execute("CREATE TABLE chat_session(id BIGINT PRIMARY KEY,title VARCHAR(120),owner_key VARCHAR(128),admin_id BIGINT,session_meta CLOB)");
        sql.execute("CREATE TABLE chat_message(id BIGINT PRIMARY KEY,session_id BIGINT,role VARCHAR(20),content CLOB,created_at TIMESTAMP)");
        sql.execute("CREATE TABLE attachment_source(id VARCHAR(36) PRIMARY KEY,owner_namespace VARCHAR(64),session_id VARCHAR(128),channel VARCHAR(16),tombstone BOOLEAN,content_sha256 VARCHAR(64),source_revision BIGINT,parser_version VARCHAR(80),size_bytes BIGINT,retained_at BIGINT,expires_at BIGINT,text_state VARCHAR(40),graph_state VARCHAR(40),vector_state VARCHAR(40),consent_epoch BIGINT)");
        sql.execute("CREATE TABLE awx_understanding_receipts(owner_namespace VARCHAR(64),session_id BIGINT,channel VARCHAR(16),kind VARCHAR(40),receipt_state VARCHAR(20),user_message_id BIGINT,user_revision BIGINT,assistant_message_id BIGINT,assistant_revision BIGINT,usum_message_id BIGINT,result_sha256 VARCHAR(64),consent_epoch BIGINT)");
        session(1, "browser-alice"); session(2, "browser-alice"); session(3, "browser-bob");
        export = new ChatConversationExportSupport(ds, mapper, null, null, null, clock);
    }
    void session(long sid, String owner) { sql.update("INSERT INTO chat_session VALUES(?,?,?,?,?)", sid, "conversation " + sid, owner, null, "{}"); }
    void message(long mid, long sid, String role, String content) { sql.update("INSERT INTO chat_message VALUES(?,?,?,?,CURRENT_TIMESTAMP)", mid, sid, role, content); }
    ChatConversationExportSupport.Selection selection(String... ids) { return new ChatConversationExportSupport.Selection(List.of(ids), ids[0]); }
    Map<String,Object> capture(String... ids) { return export.capture(alice, selection(ids)); }
    byte[] json(Map<String,Object> captured) { return export.download(alice, captured.get("exportId").toString(), "json").getBody(); }
    JsonNode context(Map<String,Object> captured) throws Exception { return mapper.readTree(json(captured)); }
    void failure(int status, String reason, org.junit.jupiter.api.function.Executable work) {
        var error = assertThrows(ChatConversationExportSupport.ExportFailure.class, work);
        assertEquals(status, error.status); assertEquals(reason, error.reason); assertEquals(0, export.workingBytes());
    }
    @Test void canonicalCaptureAndDownloadRoutesExist() {
        assertTrue(Arrays.stream(ChatApiController.class.getDeclaredMethods()).anyMatch(m -> {
            var mapping = m.getAnnotation(PostMapping.class);
            return mapping != null && Arrays.asList(mapping.value()).contains("/sessions/exports");
        }), "canonical capture route must exist before a download can be generated");
        assertTrue(Arrays.stream(ChatApiController.class.getDeclaredMethods()).anyMatch(m -> {
            var mapping = m.getAnnotation(GetMapping.class);
            return mapping != null && Arrays.asList(mapping.value()).contains("/sessions/exports/{exportId}");
        }));
    }

    @Test void wholeDbOverTwoPagesAndTwoSessionsBecomeOneImmutableJsonZip() throws Exception {
        for (int i=1;i<=400;i++) message(i, 1, i%2==0 ? "assistant" : "user", "retained " + i);
        message(401, 1, "system", pointer("snap_page", 400));
        message(402, 1, "system", "private system instructions");
        message(501, 2, "user", "previous conversation");
        var captured = capture("1", "2"); var root = context(captured);
        assertEquals(400, root.path("sessions").get(0).path("messages").size());
        assertEquals(402, root.path("sessions").get(0).path("countExported").asInt());
        assertEquals("400", root.path("sessions").get(0).path("turns").get(0).path("assistantMessageId").asText());
        assertEquals("snap_page", root.path("sessions").get(0).path("turns").get(0).path("traceSnapshotId").asText());
        assertEquals(2, root.path("sessions").size());
        byte[] original = json(captured); message(403, 1, "assistant", "later append");
        sql.update("UPDATE chat_message SET content='later edit' WHERE id=1");
        assertArrayEquals(original, json(captured));
        var response = export.download(alice, captured.get("exportId").toString(), "zip");
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        Map<String,byte[]> files=new HashMap<>();
        try (var zip=new ZipInputStream(new ByteArrayInputStream(response.getBody()), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry; while((entry=zip.getNextEntry())!=null) files.put(entry.getName(),zip.readAllBytes());
        }
        assertEquals(Set.of("context.json","manifest.json","README.txt"), files.keySet());
        assertArrayEquals(original, files.get("context.json"));
        assertEquals(captured.get("sha256"), mapper.readTree(files.get("manifest.json")).path("files").path("context.json").path("sha256").asText());
        assertEquals(captured.get("exportId"), mapper.readTree(files.get("context.json")).path("exportId").asText());
        assertTrue(root.path("snapshot").path("messageComplete").asBoolean());
        assertEquals("partial", root.path("snapshot").path("exportStatus").asText());
    }

    @Test void mixedSelectionDeniesBeforeAnyMessageReadAndAdminHasNoBypass() throws Exception {
        List<String> queries = new ArrayList<>();
        export = new ChatConversationExportSupport(observe(queries, null), mapper, null, null, null, clock);
        failure(404, "SESSION_UNAVAILABLE", () -> capture("1", "3"));
        assertTrue(queries.stream().noneMatch(q -> q.contains("chat_message") || q.contains("attachment_source")));
        assertEquals(0, export.retainedBytes());
        var admin = new ChatConversationExportSupport.Actor("admin", "browser-admin");
        failure(404, "SESSION_UNAVAILABLE", () -> export.capture(admin, selection("1")));
        failure(404, "SESSION_UNAVAILABLE", () -> capture("99"));
    }

    @Test void ownerChangeGuessingAndSharedFallbackAreRejected() {
        message(1,1,"user","own body"); var captured=capture("1"); String eid=captured.get("exportId").toString();
        failure(410,"EXPORT_EXPIRED",()->export.download(new ChatConversationExportSupport.Actor(null,"browser-bob"),eid,"json"));
        failure(410,"EXPORT_EXPIRED",()->export.download(new ChatConversationExportSupport.Actor("admin","browser-alice"),eid,"json"));
        failure(410,"EXPORT_EXPIRED",()->export.download(alice,"guess", "json"));
        failure(403,"OWNER_UNAVAILABLE",()->export.capture(new ChatConversationExportSupport.Actor(null,"ipua:shared"),selection("1")));
        failure(403,"OWNER_UNAVAILABLE",()->export.capture(new ChatConversationExportSupport.Actor(null,"system:shared"),selection("1")));
        sql.update("UPDATE chat_session SET owner_key='browser-bob' WHERE id=1");
        failure(404,"SESSION_UNAVAILABLE",()->export.download(alice,eid,"json"));
    }

    @Test void optionsAreOwnOnlyKeysetPagesEvenForAdmin() {
        var first=export.options(alice,null,1); assertEquals(true,first.get("hasMore"));
        assertEquals("2",first.get("nextCursor"));
        var next=export.options(alice,"2",1); assertEquals(false,next.get("hasMore"));
        assertEquals("1",((Map<?,?>)((List<?>)next.get("items")).get(0)).get("sessionId"));
        sql.update("INSERT INTO administrators VALUES(7,'admin')");
        sql.update("UPDATE chat_session SET admin_id=7,owner_key=NULL WHERE id=3");
        assertEquals(1,((List<?>)export.options(new ChatConversationExportSupport.Actor("admin",null),null,50).get("items")).size());
    }

    @Test void emptySelectionSizeFormatsAndLobLimitsHaveExplicitErrors() {
        failure(400,"INVALID_SELECTION",()->export.capture(alice,new ChatConversationExportSupport.Selection(List.of(),null)));
        failure(400,"INVALID_SELECTION",()->capture("0")); failure(400,"INVALID_SELECTION",()->capture("-1"));
        failure(400,"INVALID_SELECTION",()->capture("9999999999999999999"));
        failure(400,"INVALID_SELECTION",()->export.capture(alice,new ChatConversationExportSupport.Selection(Collections.nCopies(11,"1"),"1")));
        failure(400,"INVALID_FORMAT",()->export.download(alice,"guess","html"));
        message(1,1,"user","a".repeat(ChatConversationExportSupport.ROW_BYTES));
        List<String> queries=new ArrayList<>();export=new ChatConversationExportSupport(observe(queries,null),mapper,null,null,null,clock);
        failure(413,"EXPORT_TOO_LARGE",()->capture("1"));
        assertTrue(queries.stream().noneMatch(q->q.startsWith("SELECT role,created_at")),"LOB must not be read before length rejection");
        assertEquals(0,export.retainedBytes());
        sql.update("DELETE FROM chat_message"); assertNotNull(capture("1"));
    }

    @Test void ttlAndOwnerEvictionNeverRecaptureUnderOldId() {
        var a=capture("1");capture("1");capture("1");
        failure(410,"EXPORT_EXPIRED",()->json(a));
        var b=capture("1");clock.now=clock.now.plusSeconds(301);
        failure(410,"EXPORT_EXPIRED",()->json(b));assertEquals(0,export.retainedBytes());
    }

    @Test void emptyCaptureAndMaskingExcludeRawSystemAndOwnerIdentity() throws Exception {
        assertEquals("empty",context(capture("1")).path("snapshot").path("exportStatus").asText());
        message(1,1,"user","안녕 secret="+"sensitive-fixture"+" Cookie: synthetic-cookie\nownerKey=browser-alice\nrunId=synthetic-run");
        message(2,1,"assistant","안녕하세요");message(3,1,"system","raw system sentinel");
        String body=new String(json(capture("1")),StandardCharsets.UTF_8);
        for(String forbidden:List.of("sensitive-fixture","synthetic-cookie","browser-alice","synthetic-run","raw system sentinel")) assertFalse(body.contains(forbidden),forbidden);
        assertTrue(body.contains("안녕"));assertFalse(body.contains("owner_namespace"));
    }

    @Test void conflictingOrLegacyPointersNeverBindByPosition() throws Exception {
        message(1,1,"assistant","answer");message(2,1,"system",pointer("snap_one",1));
        message(3,1,"system",pointer("snap_two",1));message(4,1,"system","?TRACESNAP?snap_legacy");
        var turns=context(capture("1")).path("sessions").get(0).path("turns");
        assertEquals("conflicting_pointer",turns.get(0).path("reason").asText());
        assertEquals("legacy_unbound",turns.get(2).path("reason").asText());
        assertFalse(turns.get(0).has("assistantMessageId"));
    }

    @Test void foreignSessionRingAndSameRequestHashEventsDoNotLeak() throws Exception {
        message(1,1,"assistant","answer");message(2,1,"system",pointer("snap_one",1));
        var traces=mock(TraceSnapshotStore.class);var events=mock(DebugEventStore.class);
        var owned=new TraceSnapshotStore.TraceSnapshot("snap_one",1,"now",SafeRedactor.hashValue("1"),SafeRedactor.hashValue("1"),
                "hash:123456789abc","hash:abcdef123456","safe","GET","",200,null,false,0,Map.of(),Map.of(),Map.of(),null,false);
        when(traces.get("snap_one")).thenReturn(Optional.of(owned));
        var own=new DebugEvent("e-own",Instant.EPOCH,0,DebugEventLevel.INFO,DebugProbeType.values()[0],"fp","own event",SafeRedactor.hashValue("1"),null,null,null,null,Map.of(),null,null);
        var foreign=new DebugEvent("e-foreign",Instant.EPOCH,0,DebugEventLevel.INFO,DebugProbeType.values()[0],"fp","foreign secret",SafeRedactor.hashValue("3"),null,null,null,null,Map.of(),null,null);
        when(events.page(any(),any(),isNull(),eq(200))).thenReturn(new DebugEventStore.EventPage(List.of(own,foreign),null,false,"ok"));
        export=new ChatConversationExportSupport(ds,mapper,traces,events,null,clock);
        var result=context(capture("1")).path("sessions").get(0).path("turns").get(0).path("events");
        assertEquals(1,result.path("items").size());assertEquals("e-own",result.path("items").get(0).path("id").asText());
        assertEquals(1,result.path("unboundExcludedCount").asInt());
        var foreignSnapshot=new TraceSnapshotStore.TraceSnapshot("snap_one",1,"now",SafeRedactor.hashValue("3"),SafeRedactor.hashValue("3"),
                "hash:123456789abc","hash:abcdef123456","safe","GET","",200,null,false,0,Map.of(),Map.of(),Map.of(),null,false);
        when(traces.get("snap_one")).thenReturn(Optional.of(foreignSnapshot));clearInvocations(events);
        assertEquals("session_hash_missing_or_mismatch",context(capture("1")).path("sessions").get(0).path("turns").get(0).path("events").path("reason").asText());
        verifyNoInteractions(events);
    }

    @Test void realProducerChatSidBindsOnlyAuthorizedRingAndExactEvents() throws Exception {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var traces = new TraceSnapshotStore(beans.getBeanProvider(com.example.lms.service.trace.TraceHtmlBuilder.class));
        org.springframework.test.util.ReflectionTestUtils.setField(traces, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(traces, "allowReasonsCsv", "");
        org.springframework.test.util.ReflectionTestUtils.setField(traces, "denyReasonsCsv", "");
        org.springframework.test.util.ReflectionTestUtils.setField(traces, "maxValueLen", 1000);
        org.springframework.test.util.ReflectionTestUtils.setField(traces, "maxSize", 20);
        String snapshotId;
        try {
            org.slf4j.MDC.put("sid", "chat-1");
            org.slf4j.MDC.put("traceId", "fixture-trace");
            org.slf4j.MDC.put("x-request-id", "fixture-request");
            snapshotId = traces.captureCustom("unit_test", "POST", "/api/chat", 200, null,
                    Map.of("finalAnswer.releaseAllowed", true), null, false);
        } finally { org.slf4j.MDC.clear(); }
        var snapshot = traces.get(snapshotId).orElseThrow();
        assertEquals(SafeRedactor.hashValue("chat-1"), snapshot.sessionId());
        assertEquals(snapshot.sessionId(), snapshot.sid());
        message(1, 1, "assistant", "answer"); message(2, 1, "system", pointer(snapshotId, 1));
        var events = mock(DebugEventStore.class);
        List<DebugEvent> rows = new ArrayList<>();
        for (String sid : List.of("chat-1", "1", "chat-3", "unknown")) {
            rows.add(new DebugEvent("e-" + sid, Instant.EPOCH, 0, DebugEventLevel.INFO,
                    DebugProbeType.values()[0], "fp", "fixture", SafeRedactor.hashValue(sid),
                    null, null, null, null, Map.of(), null, null));
        }
        when(events.page(eq(snapshot.requestId()), eq(snapshot.traceId()), isNull(), eq(200)))
                .thenReturn(new DebugEventStore.EventPage(rows, null, false, "ok"));
        export = new ChatConversationExportSupport(ds, mapper, traces, events, null, clock);
        var turn = context(capture("1")).path("sessions").get(0).path("turns").get(0);
        assertEquals("safe_typed_projection_only", turn.path("trace").path("ring").path("reason").asText());
        assertEquals(1, turn.path("events").path("items").size());
        assertEquals("e-chat-1", turn.path("events").path("items").get(0).path("id").asText());
        assertEquals(3, turn.path("events").path("unboundExcludedCount").asInt());
        verify(events).page(snapshot.requestId(), snapshot.traceId(), null, 200);
    }

    @Test void inFlightCaptureDeclaresFenceAndRequiresFreshCaptureAfterCommit() throws Exception {
        message(1, 1, "user", "question");
        var runs = mock(com.example.lms.service.chat.ChatRunRegistry.class);
        when(runs.isRunning(1L)).thenReturn(true);
        export = new ChatConversationExportSupport(ds, mapper, null, null, runs, clock);
        try (Connection writer = ds.getConnection()) {
            writer.setAutoCommit(false);
            try (var insert = writer.prepareStatement("INSERT INTO chat_message VALUES(2,1,'assistant','answer',CURRENT_TIMESTAMP)")) {
                insert.executeUpdate();
            }
            var captured = capture("1"); byte[] original = json(captured);
            var before = mapper.readTree(original);
            assertEquals(1, before.path("sessions").get(0).path("messages").size());
            assertEquals("in_progress", before.path("snapshot").path("latestTurnCoverage").asText());
            assertTrue(before.path("snapshot").path("completeWithinFence").asBoolean());
            assertEquals("captured_db_fence", before.path("snapshot").path("messageCompleteScope").asText());
            writer.commit(); when(runs.isRunning(1L)).thenReturn(false);
            assertArrayEquals(original, json(captured));
            var after = context(capture("1"));
            assertEquals(2, after.path("sessions").get(0).path("messages").size());
            assertEquals("persisted_within_fence", after.path("snapshot").path("latestTurnCoverage").asText());
        }
    }

    @Test void postFenceCommitDoesNotChangeRetainedCapture() throws Exception {
        message(1, 1, "user", "question");
        try (Connection writer = ds.getConnection()) {
            writer.setAutoCommit(false);
            try (var insert = writer.prepareStatement("INSERT INTO chat_message VALUES(2,1,'assistant','answer',CURRENT_TIMESTAMP)")) {
                insert.executeUpdate();
            }
            var committed = new java.util.concurrent.atomic.AtomicBoolean();
            export = new ChatConversationExportSupport(observe(new ArrayList<>(), () -> {
                if (committed.compareAndSet(false, true)) {
                    try { writer.commit(); } catch (java.sql.SQLException error) { throw new RuntimeException(error); }
                }
            }), mapper, null, null, null, clock);
            var captured = capture("1"); byte[] original = json(captured);
            assertEquals(1, mapper.readTree(original).path("sessions").get(0).path("messages").size());
            assertEquals("awaiting_assistant", mapper.readTree(original).path("snapshot").path("latestTurnCoverage").asText());
            assertEquals(2, context(capture("1")).path("sessions").get(0).path("messages").size());
            assertArrayEquals(original, json(captured));
        }
    }

    @Test void twoHundredFiftySevenAttachmentsSurviveRingLossWithoutRawUnits() throws Exception {
        String namespace=AttachmentOwnerIdentity.forAnonymous("browser-alice").hash();
        for(int i=0;i<257;i++)sql.update("INSERT INTO attachment_source VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",String.format("a%03d",i),namespace,"1","GENERAL",false,"a".repeat(64),1,"parser-v1",12,clock.millis(),0,"READY","NOT_INDEXED","READY",1);
        sql.update("INSERT INTO attachment_source VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)","expired",namespace,"1","GENERAL",false,"a".repeat(64),1,"parser-v1",12,clock.millis(),clock.millis()-1,"READY","NOT_INDEXED","READY",1);
        var attachments=context(capture("1")).path("sessions").get(0).path("attachments");
        assertEquals(257,attachments.path("countExported").asInt());assertEquals(257,attachments.path("items").size());
        assertTrue(attachments.path("historyComplete").asBoolean());assertFalse(attachments.toString().contains(namespace));
        sql.update("UPDATE attachment_source SET consent_epoch=2 WHERE id='a000'");
        assertEquals("excluded",context(capture("1")).path("sessions").get(0).path("attachments").path("items").get(0).path("status").asText());
    }

    @Test void receiptRequiresExactRolesRevisionsResultAndCurrentConsent() throws Exception {
        String summary="{\"citations\":[{\"url\":\"https://example.com/private?credential=fixture\",\"title\":\"public\"}]}";
        message(1,1,"user","question");message(2,1,"assistant","answer");message(3,1,"system",ChatConversationExportSupport.USUM+summary);
        sql.update("INSERT INTO awx_understanding_receipts VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",AttachmentOwnerIdentity.forAnonymous("browser-alice").hash(),1,"GENERAL","UNDERSTANDING","PERSISTED",1,rev("user","question"),2,rev("assistant","answer"),3,sha(summary),1);
        var data=context(capture("1")).path("sessions").get(0).path("understanding").get(0);
        assertFalse(data.path("unboundToTurn").asBoolean());assertEquals("2",data.path("assistantMessageId").asText());
        assertEquals("https://example.com",data.path("citations").get(0).path("url").asText());
        sql.update("UPDATE chat_message SET content='changed answer' WHERE id=2");
        assertTrue(context(capture("1")).path("sessions").get(0).path("understanding").get(0).path("unboundToTurn").asBoolean());
    }

    @Test void serializableCaptureRetainsOriginalContentAcrossCountInvariantConcurrentUpdate() throws Exception {
        message(1,1,"user","original body"); message(2,1,"assistant","original answer");
        boolean[] once={false};List<String> queries=new ArrayList<>();
        export=new ChatConversationExportSupport(observe(queries, ()->{
            if(!once[0]) { once[0]=true;sql.update("UPDATE chat_message SET content='concurrent update' WHERE id=1"); }
        }),mapper,null,null,null,clock);
        var root=context(capture("1"));
        assertTrue(once[0]);assertEquals("original body",root.path("sessions").get(0).path("messages").get(0).path("content").asText());
        assertEquals("concurrent update",sql.queryForObject("SELECT content FROM chat_message WHERE id=1",String.class));
    }

    @Test void derivedEventsHaveOneSharedCaptureBudgetBeforeOutputAssembly() throws Exception {
        var traces=mock(TraceSnapshotStore.class);var events=mock(DebugEventStore.class);
        for(int i=1;i<=3;i++) {
            message(i*2-1,1,"assistant","answer "+i);message(i*2,1,"system",pointer("snap_"+i,i*2-1));
            var snapshot=new TraceSnapshotStore.TraceSnapshot("snap_"+i,1,"now",SafeRedactor.hashValue("1"),SafeRedactor.hashValue("1"),
                "hash:123456789abc","hash:abcdef123456","safe","GET","",200,null,false,0,Map.of(),Map.of(),Map.of(),null,false);
            when(traces.get("snap_"+i)).thenReturn(Optional.of(snapshot));
        }
        int[] calls={0};
        when(events.page(any(),any(),any(),eq(200))).thenAnswer(inv->{
            int number=++calls[0];List<DebugEvent> page=new ArrayList<>();
            for(int i=0;i<200;i++)page.add(new DebugEvent("e-"+number+"-"+i,Instant.EPOCH,0,DebugEventLevel.INFO,DebugProbeType.values()[0],"fp","event",SafeRedactor.hashValue("1"),null,null,null,null,Map.of(),null,null));
            return new DebugEventStore.EventPage(page,"cursor-"+number,true,"ok");
        });
        export=new ChatConversationExportSupport(ds,mapper,traces,events,null,clock);
        var data=context(capture("1"));
        assertTrue(calls[0]<=20,"events must share a 4000-row budget across the entire capture, calls="+calls[0]);
        assertTrue(data.toString().contains("event_capture_limit"));
        assertEquals(0,export.workingBytes());
    }

    @Test void captureBusyAndFailedCaptureReleaseWorkingReservations() throws Exception {
        message(1,1,"user","bounded body");
        CountDownLatch entered=new CountDownLatch(1), proceed=new CountDownLatch(1);
        export=new ChatConversationExportSupport(observe(new ArrayList<>(),()->{
            entered.countDown();try { if(!proceed.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture_timeout"); }
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}
        }),mapper,null,null,null,clock);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->capture("1"));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(ChatConversationExportSupport.CAPTURE_WORK,export.workingBytes());
            var error=assertThrows(ChatConversationExportSupport.ExportFailure.class,()->capture("1"));
            assertEquals(503,error.status);assertEquals("EXPORT_BUSY",error.reason);
            proceed.countDown();assertNotNull(first.get(5,TimeUnit.SECONDS));assertEquals(0,export.workingBytes());
            sql.update("UPDATE chat_message SET content=? WHERE id=1","x".repeat(ChatConversationExportSupport.ROW_BYTES));
            failure(413,"EXPORT_TOO_LARGE",()->capture("1"));
        } finally {proceed.countDown();executor.shutdownNow();}
    }

    @Test void totalRowsAndTotalSerializedBytesRejectInsteadOfTruncating() {
        for(int i=1;i<=10_001;i++)message(i,1,"user","small");
        failure(413,"EXPORT_TOO_LARGE",()->capture("1"));
        sql.update("DELETE FROM chat_message");
        for(int i=1;i<=34;i++)message(i,1,"user","x".repeat(500_000));
        failure(413,"EXPORT_TOO_LARGE",()->capture("1"));assertEquals(0,export.retainedBytes());
    }

    @Test void zipBusyFailureAndConcurrentCaptureReleaseReservations() throws Exception {
        message(1,1,"user","zip body");
        CountDownLatch entered=new CountDownLatch(1),proceed=new CountDownLatch(1);
        var firstManifest=new java.util.concurrent.atomic.AtomicBoolean(true);
        ObjectMapper blocking=new ObjectMapper(){
            @Override public void writeValue(java.io.OutputStream out,Object value)throws java.io.IOException {
                if(value instanceof Map<?,?> map && map.containsKey("files") && firstManifest.getAndSet(false)){
                    entered.countDown();
                    try{if(!proceed.await(5,TimeUnit.SECONDS))throw new java.io.IOException("fixture_timeout");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
                    throw new java.io.IOException("synthetic_zip_failure");
                }
                super.writeValue(out,value);
            }
        };
        export=new ChatConversationExportSupport(ds,blocking,null,null,null,clock);
        String eid=capture("1").get("exportId").toString();
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            var first=executor.submit(()->export.download(alice,eid,"zip"));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(ChatConversationExportSupport.ZIP_WORK,export.workingBytes());
            var busy=assertThrows(ChatConversationExportSupport.ExportFailure.class,()->export.download(alice,eid,"zip"));
            assertEquals(503,busy.status);assertEquals("EXPORT_BUSY",busy.reason);
            assertNotNull(capture("1"));assertEquals(ChatConversationExportSupport.ZIP_WORK,export.workingBytes());
            proceed.countDown();var failed=assertThrows(ExecutionException.class,()->first.get(5,TimeUnit.SECONDS));
            assertInstanceOf(ChatConversationExportSupport.ExportFailure.class,failed.getCause());
            assertEquals(0,export.workingBytes());assertNotNull(export.download(alice,eid,"zip").getBody());
            assertEquals(0,export.workingBytes());
        }finally{proceed.countDown();executor.shutdownNow();}
    }

    @Test void globalCacheEvictsAcrossDistinctOwnersWithinByteBudget(){
        String eid=capture("1").get("exportId").toString();
        for(int i=4;i<=8;i++){
            String owner="browser-owner-"+i;session(i,owner);message(i,i,"user","owned body");
            var actor=new ChatConversationExportSupport.Actor(null,owner);
            assertNotNull(export.capture(actor,selection(Integer.toString(i))));
            assertTrue(export.retainedBytes()<=ChatConversationExportSupport.CACHE_BYTES);
        }
        failure(410,"EXPORT_EXPIRED",()->export.download(alice,eid,"json"));
    }

    DataSource observe(List<String> queries, Runnable beforeContent) {
        DataSource proxy=mock(DataSource.class);
        try { when(proxy.getConnection()).thenAnswer(inv->{
            Connection delegate=ds.getConnection();return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(object,method,args)->{
                if(method.getName().equals("prepareStatement")) {
                    String query=(String)args[0];queries.add(query);
                    if(query.startsWith("SELECT role,created_at") && beforeContent!=null)beforeContent.run();
                }
                try {return method.invoke(delegate,args);} catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
        });}catch(Exception error){throw new RuntimeException(error);}return proxy;
    }
    static String pointer(String snapshot,long assistant) {
        String fields="storageMode=durable_fallback\nreason=test\nmethod=POST\npathHash=none\nassistantMessageId="+assistant+"\n";
        return "?TRACESNAP?"+snapshot+"|v3|"+Base64.getUrlEncoder().withoutPadding().encodeToString(fields.getBytes(StandardCharsets.UTF_8));
    }
    static String sha(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    static long rev(String role,String content)throws Exception{long v=ByteBuffer.wrap(HexFormat.of().parseHex(sha(role+"\u0000"+content))).getLong()&Long.MAX_VALUE;return v==0?1:v;}
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-07T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
}
