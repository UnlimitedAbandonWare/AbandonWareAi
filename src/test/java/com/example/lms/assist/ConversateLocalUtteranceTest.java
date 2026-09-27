package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversateLocalUtteranceTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String OWNER = "d".repeat(64);
    @Test void configuredCloudModelCannotOverwriteTheObservedLocalWhisperModel() {
        var cloud=org.mockito.Mockito.mock(ConversateCloudStt.class);
        org.mockito.Mockito.when(cloud.diagnostics()).thenReturn(Map.of("provider","deepgram","model","nova-3","routing","single","state","disabled"));
        try(var sessions=new ConversateSessionService()) {
            var bridge=new ConversateAsrBridge(sessions,json,null,cloud);
            try {
                var view=bridge.displayDiagnostics(new ConversateSessionService.AudioMetrics(0,0,0,0,0,"READY",Map.of("provider","whisper","model","turbo")));
                assertEquals("turbo",view.get("model"));assertEquals("nova-3",view.get("configuredCloudModel"));
            } finally {bridge.close();}
        }
    }
    static class Pipeline extends ConversateAnswerPipeline {
        final List<String> questions = new CopyOnWriteArrayList<>();
        final List<List<String>> contexts = new CopyOnWriteArrayList<>();
        @Override boolean usesApiCues() { return true; }
        @Override public Outcome answerLive(String question, List<String> context,
                List<PreparedMaterialReader.Material> docs, long now, String path, String id, boolean forceHint,
                int hintTargetChars) {
            contexts.add(context);questions.add(question);
            return new Outcome("NO_CUE", null);
        }
    }
    @Test void confirmedSegmentsDisplayImmediatelyButOnlyCompleteUtteranceGenerates() throws Exception {
        var pipeline = new Pipeline();
        try (var sessions = new ConversateSessionService(Clock.systemUTC(), pipeline)) {
            var fake = new ConversateAsrBridgeTest.FakeTransport();
            {
                var bridge = new ConversateAsrBridge(sessions, json, (events, failure) -> {
                    fake.events = events; events.accept(json.createObjectNode().put("type", "ready")); return fake;
                });
                var s = sessions.start(OWNER);
                try {
                    bridge.start(OWNER, s.assistId(), s.epoch());
                    send(fake, "asr-1", "asr-1", 1, true, false, "불확정성 원리를 설명하고");
                    assertEquals(0, sessions.status(OWNER, s.assistId()).metrics().inFlight());
                    assertEquals("불확정성 원리를 설명하고", sessions.status(OWNER, s.assistId()).caption().text());
                    send(fake, "asr-2", "asr-1", 1, false, false, "측정기를 개선하면");
                    assertEquals(0, pipeline.questions.size());
                    send(fake, "asr-2", "asr-1", 2, true, true, "측정기를 개선하면 해결되는지도 알려 줘");
                    awaitCalls(pipeline, 1);
                    assertEquals("불확정성 원리를 설명하고 측정기를 개선하면 해결되는지도 알려 줘", pipeline.questions.get(0));
                    send(fake, "asr-2", "asr-1", 3, true, true, "측정기를 개선하면 해결되는지도 알려 줘");
                    assertEquals(1, pipeline.questions.size());
                    assertEquals(1, sessions.status(OWNER, s.assistId()).audio().finals());
                    send(fake, "asr-3", "asr-3", 1, true, true, "불확정성 원리를 설명하고 측정기를 개선하면 해결되는지도 알려 줘");
                    awaitCalls(pipeline, 2);
                } finally { bridge.close(); }
            }
        }
    }
    @Test void endpointAfterAnExactSegmentBoundaryRetainsLastText() throws Exception {
        var pipeline = new Pipeline();
        try (var sessions = new ConversateSessionService(Clock.systemUTC(), pipeline)) {
            var fake = new ConversateAsrBridgeTest.FakeTransport();
            var bridge = new ConversateAsrBridge(sessions, json, (events, failure) -> {
                fake.events = events; events.accept(json.createObjectNode().put("type", "ready")); return fake;
            });
            var s = sessions.start(OWNER);
            try {
                bridge.start(OWNER, s.assistId(), s.epoch());
                send(fake, "asr-1", "asr-1", 5, true, false, "측정기 문제가 아닌 이유가 뭐야?");
                send(fake, "asr-1", "asr-1", 6, true, true, "");
                awaitCalls(pipeline, 1);
                assertEquals("측정기 문제가 아닌 이유가 뭐야?", pipeline.questions.get(0));
            } finally { bridge.close(); }
        }
    }
    @Test void longQuestionKeepsAllSegmentsAndItsTailForTheNextQuestion() throws Exception {
        var pipeline = new Pipeline();
        try (var sessions = new ConversateSessionService(Clock.systemUTC(), pipeline)) {
            var fake = new ConversateAsrBridgeTest.FakeTransport();
            var bridge = new ConversateAsrBridge(sessions, json, (events, failure) -> {
                fake.events = events; events.accept(json.createObjectNode().put("type", "ready")); return fake;
            });
            var s = sessions.start(OWNER);
            try {
                bridge.start(OWNER, s.assistId(), s.epoch());
                String segment = "위치와 운동량의 불확실성에 관한 조건을 유지해 주세요. ".repeat(40);
                for (int i = 1; i <= 4; i++) send(fake, "asr-"+i, "asr-1", 1, true, i==4,
                        segment + (i==4 ? "마지막 조건은 측정 오류 때문이라는 설명을 빼는 거야." : ""));
                awaitCalls(pipeline, 1);
                assertTrue(pipeline.questions.get(0).length() > 4096);
                assertTrue(pipeline.questions.get(0).endsWith("측정 오류 때문이라는 설명을 빼는 거야."));
                send(fake, "asr-5", "asr-5", 1, true, true, "그럼 측정기를 더 좋게 만들면 해결돼?");
                awaitCalls(pipeline, 2);
                assertTrue(pipeline.contexts.get(1).contains(pipeline.questions.get(0)));
            } finally { bridge.close(); }
        }
    }
    @Test void ownedChildFinishConsumesFinalBeforeAcknowledgingAndDoesNotReportCrash(@TempDir Path dir) throws Exception {
        String python = System.getenv("CONVERSATE_TEST_PYTHON");
        org.junit.jupiter.api.Assumptions.assumeTrue(python != null && Files.isRegularFile(Path.of(python)));
        Path script = dir.resolve("finish.py");
        Files.writeString(script, "import json,sys\nprint('{\"type\":\"ready\"}',flush=True)\nfor line in sys.stdin:\n if json.loads(line).get('type')=='finish':\n  print('{\"type\":\"transcript\",\"final\":true}',flush=True)\n  print('{\"type\":\"finished\"}',flush=True)\n  break\n");
        var ready = new CountDownLatch(1); var finals = new AtomicInteger(); var failures = new AtomicInteger();
        var child = new ConversateAsrBridge.Child(json, python, script.toString(), dir.toString(), 2,
                event -> { if (event.path("type").asText().equals("ready")) ready.countDown(); else finals.incrementAndGet(); },
                reason -> failures.incrementAndGet());
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            child.finish().get(3, TimeUnit.SECONDS);
            assertEquals(1, finals.get());
            assertEquals(0, failures.get());
            assertSame(child.finish(), child.finish());
        } finally { child.close().get(2, TimeUnit.SECONDS); }
    }
    private void send(ConversateAsrBridgeTest.FakeTransport fake, String segment, String group,
            int revision, boolean finalized, boolean end, String text) {
        fake.events.accept(json.createObjectNode().put("type", "transcript").put("utteranceId", segment)
                .put("groupId", group).put("revision", revision).put("final", finalized).put("utteranceEnd", end).put("text", text));
    }
    private static void awaitCalls(Pipeline pipeline, int expected) throws Exception {
        for (int i = 0; i < 100 && pipeline.questions.size() < expected; i++) Thread.sleep(10);
        assertEquals(expected, pipeline.questions.size());
    }
}
