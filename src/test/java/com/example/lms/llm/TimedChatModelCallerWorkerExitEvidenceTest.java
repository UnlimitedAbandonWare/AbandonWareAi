package com.example.lms.llm;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TimedChatModelCallerWorkerExitEvidenceTest {
    @Test @Timeout(10)
    void cancellationAckDoesNotClaimWorkerReturnBeforeTheProviderActuallyReturns() throws Exception {
        TimedChatModelCaller.resetSharedExecutorForTest(1, 1);
        var registry = new ChatRunRegistry();
        ReflectionTestUtils.setField(registry, "replayCapacity", 16);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 300);
        var run = registry.beginOrJoin(981L).context();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var events = new LinkedBlockingQueue<String>();
        var logger = (Logger) LoggerFactory.getLogger("com.example.lms.llm.ModelRuntimeHealthTracker.requestProof");
        var capture = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                String message = event.getFormattedMessage();
                if (message.contains("[LLM_WORKER_EXIT]") && message.contains(run.redactedRunIdentity())) events.add(message);
            }
        };
        capture.setContext(logger.getLoggerContext());
        capture.start();
        logger.addAppender(capture);
        var caller = Executors.newSingleThreadExecutor();
        ChatModel provider = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; }
                    catch (InterruptedException expected) { interrupted.countDown(); }
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("synthetic late response")).build();
            }
        };
        try {
            var answer = caller.submit(() -> {
                try (var scope = ChatRunExecutionContext.bind(run)) {
                    return TimedChatModelCaller.chat(provider, List.of(UserMessage.from("synthetic private input")),
                            Duration.ofMillis(10), "chat_draft", "synthetic-fixture-model");
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(registry.cancelExact(981L, run.clientToken()));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> answer.get(2, TimeUnit.SECONDS));
            assertNull(events.poll(150, TimeUnit.MILLISECONDS), "Stop acceptance must not claim physical return");
            release.countDown();
            String physicalReturn = events.poll(2, TimeUnit.SECONDS);
            assertNotNull(physicalReturn, "Physical provider return needs a correlated worker exit record");
            assertTrue(physicalReturn.contains("phase=worker_exited"));
            assertTrue(physicalReturn.contains("scope=model_call"));
            assertTrue(physicalReturn.contains("stage=chat_draft"));
            assertTrue(physicalReturn.contains("cancellationRequested=true"));
            assertTrue(physicalReturn.contains("modelHash=hash:"));
            assertFalse(physicalReturn.contains("synthetic-fixture-model"));
            assertFalse(physicalReturn.contains("synthetic private input"));
            assertFalse(physicalReturn.contains("synthetic late response"));
            assertNull(events.poll(100, TimeUnit.MILLISECONDS), "One admitted provider call returns once");
            assertEquals(1, calls.get());
            assertFalse(registry.cancelExact(981L, run.clientToken()));
        } finally {
            release.countDown();
            caller.shutdownNow();
            assertTrue(TimedChatModelCaller.shutdownSharedExecutorForTest(1000));
            logger.detachAppender(capture);
            capture.stop();
        }
    }
}
