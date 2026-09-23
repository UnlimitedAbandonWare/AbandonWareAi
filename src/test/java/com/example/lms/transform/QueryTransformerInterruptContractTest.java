package com.example.lms.transform;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTransformerInterruptContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    @Timeout(5)
    void callerInterruptStatusIsRestoredAfterFailSoftReturn() throws Exception {
        try (Harness harness = Harness.create()) {
            CallerRun caller = harness.startCaller("caller-interrupt-status");
            assertTrue(harness.model.started.await(1, TimeUnit.SECONDS), "LLM worker did not start");

            caller.thread.interrupt();

            assertTrue(caller.done.await(1, TimeUnit.SECONDS), "interrupted caller did not return");
            assertNull(caller.failure.get(), () -> "caller failed: " + caller.failure.get());
            assertTrue(caller.interruptedAfterReturn.get(),
                    "QueryTransformer must restore the request thread interrupt before returning fail-soft");
        }
    }

    @Test
    @Timeout(5)
    void callerInterruptPhysicallyTerminatesBlockedWorkerBehindCancelShield() throws Exception {
        try (Harness harness = Harness.create()) {
            CallerRun caller = harness.startCaller("caller-cancels-worker");
            assertTrue(harness.model.started.await(1, TimeUnit.SECONDS), "LLM worker did not start");

            caller.thread.interrupt();

            assertTrue(caller.done.await(1, TimeUnit.SECONDS), "interrupted caller did not return");
            assertNull(caller.failure.get(), () -> "caller failed: " + caller.failure.get());
            assertTrue(harness.model.interrupted.await(1, TimeUnit.SECONDS),
                    "cancelled QueryTransformer worker did not receive an interrupt");
            assertTrue(harness.model.exited.await(1, TimeUnit.SECONDS),
                    "cancelled QueryTransformer worker did not exit");
            assertTrue(harness.rawExecutor.taskExited.await(1, TimeUnit.SECONDS),
                    "executor did not observe the cancelled task exit");
            assertTrue(harness.rawExecutor.interruptedAtTaskExit.get(),
                    "runLLM must preserve the worker interrupt until the owned task exits");
        }
    }

    @Test
    @Timeout(5)
    void forceKillPhysicallyTerminatesBlockedWorker() throws Exception {
        try (Harness harness = Harness.create()) {
            setPrivateLong(harness.transformer, "inflightTimeoutMs", 50L);
            CallerRun caller = harness.startCaller("force-kill-worker");
            assertTrue(harness.model.started.await(1, TimeUnit.SECONDS), "LLM worker did not start");

            assertTrue(caller.done.await(2, TimeUnit.SECONDS), "force-kill did not release the caller");
            assertNull(caller.failure.get(), () -> "caller failed: " + caller.failure.get());
            assertTrue(harness.model.interrupted.await(1, TimeUnit.SECONDS),
                    "force-kill did not interrupt the blocked worker");
            assertTrue(harness.model.exited.await(1, TimeUnit.SECONDS),
                    "force-killed worker did not exit");
        }
    }

    private static final class Harness implements AutoCloseable {
        private final BlockingModel model;
        private final ObservingExecutor rawExecutor;
        private final ExecutorService shieldedExecutor;
        private final QueryTransformer transformer;
        private final Method cachedLlm;

        private Harness(BlockingModel model,
                        ObservingExecutor rawExecutor,
                        ExecutorService shieldedExecutor,
                        QueryTransformer transformer,
                        Method cachedLlm) {
            this.model = model;
            this.rawExecutor = rawExecutor;
            this.shieldedExecutor = shieldedExecutor;
            this.transformer = transformer;
            this.cachedLlm = cachedLlm;
        }

        static Harness create() throws Exception {
            BlockingModel model = new BlockingModel();
            ObservingExecutor rawExecutor = new ObservingExecutor();
            ExecutorService contextAware = new ContextAwareExecutorService(rawExecutor);
            ExecutorService shielded = new CancelShieldExecutorService(contextAware, "llmFastExecutor");
            QueryTransformer transformer = new QueryTransformer(model, Map.of(), null);
            setPrivateField(transformer, "llmFastExecutor", shielded);
            setPrivateBoolean(transformer, "novaOrchEnabled", true);
            setPrivateBoolean(transformer, "novaOrchQueryTransformerEnabled", true);
            setPrivateBoolean(transformer, "novaOrchQueryTransformerCheapEnabled", true);
            setPrivateLong(transformer, "llmTimeoutMsHint", 10_000L);
            setPrivateLong(transformer, "llmHintTimeoutFloorMs", 0L);
            setPrivateLong(transformer, "inflightTimeoutMs", 30_000L);

            Method cachedLlm = QueryTransformer.class.getDeclaredMethod("cachedLlm", String.class);
            cachedLlm.setAccessible(true);
            return new Harness(model, rawExecutor, shielded, transformer, cachedLlm);
        }

        CallerRun startCaller(String prompt) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    cachedLlm.invoke(transformer, prompt);
                } catch (InvocationTargetException e) {
                    failure.set(e.getCause() == null ? e : e.getCause());
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
                    done.countDown();
                }
            }, "qtx-contract-caller");
            caller.setDaemon(true);
            caller.start();
            return new CallerRun(caller, done, failure, interruptedAfterReturn);
        }

        @Override
        public void close() throws Exception {
            model.release.countDown();
            shieldedExecutor.shutdownNow();
            rawExecutor.shutdownNow();
            assertTrue(rawExecutor.awaitTermination(2, TimeUnit.SECONDS),
                    "test executor did not terminate");
        }
    }

    private record CallerRun(Thread thread,
                             CountDownLatch done,
                             AtomicReference<Throwable> failure,
                             AtomicBoolean interruptedAfterReturn) {
    }

    private static final class BlockingModel implements ChatModel {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            started.countDown();
            try {
                release.await();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("late response"))
                        .build();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                exited.countDown();
            }
        }
    }

    private static final class ObservingExecutor extends ThreadPoolExecutor {
        private final AtomicBoolean interruptedAtTaskExit = new AtomicBoolean();
        private final CountDownLatch taskExited = new CountDownLatch(1);

        private ObservingExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "qtx-contract-worker");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        protected void afterExecute(Runnable runnable, Throwable failure) {
            interruptedAtTaskExit.set(Thread.currentThread().isInterrupted());
            taskExited.countDown();
            super.afterExecute(runnable, failure);
        }
    }

    private static void setPrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setPrivateLong(Object target, String fieldName, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
