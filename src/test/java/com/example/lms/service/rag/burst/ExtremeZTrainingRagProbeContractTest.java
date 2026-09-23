package com.example.lms.service.rag.burst;

import com.example.lms.orchestration.ExecutionPlan;
import com.example.lms.search.TraceStore;
import com.example.lms.service.TrainingService;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.SelfAskPlanner;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtremeZTrainingRagProbeContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void parallelFanoutAddsTrainingRagProbeWhenTrainingServiceIsAvailable() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setMaxSubQueries(2);
        props.setMaxMergedDocs(8);
        RecordingExecutor executor = new RecordingExecutor();
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 2)).thenReturn(List.of("RAG evidence official", "RAG evidence pdf"));
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        when(webRetriever.retrieve(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            return List.of(Content.from(query.text() + " web"));
        });
        TrainingService trainingService = mock(TrainingService.class);
        when(trainingService.findTrainingRagSamples(anyString(), anyInt()))
                .thenAnswer(invocation -> List.of("training hint for " + invocation.getArgument(0, String.class)));

        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                planner,
                webRetriever,
                null,
                null,
                null,
                props,
                executor,
                trainingService);

        List<Content> out = handler.execute("RAG evidence", activeExtremeZDecision());

        assertEquals(4, out.size());
        assertEquals(6, TraceStore.get("extremez.execute.parallel.taskCount"));
        assertEquals(6, TraceStore.get("extremez.parallelBranchCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.trainingRagProbe.enabled"));
        assertEquals(2, TraceStore.get("extremez.trainingRagProbe.returnedCount"));
        verify(trainingService, times(2)).findTrainingRagSamples(anyString(), anyInt());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("RAG evidence official"));
    }

    @Test
    void sequentialFanoutCountsTrainingRagProbeWhenTrainingServiceIsAvailable() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setMaxSubQueries(2);
        props.setMaxMergedDocs(8);
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 2)).thenReturn(List.of("RAG evidence official", "RAG evidence pdf"));
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        when(webRetriever.retrieve(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            return List.of(Content.from(query.text() + " web"));
        });
        TrainingService trainingService = mock(TrainingService.class);
        when(trainingService.findTrainingRagSamples(anyString(), anyInt()))
                .thenAnswer(invocation -> List.of("training hint for " + invocation.getArgument(0, String.class)));

        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                planner,
                webRetriever,
                null,
                null,
                null,
                props,
                null,
                trainingService);

        List<Content> out = handler.execute("RAG evidence", activeExtremeZDecision());

        assertEquals(4, out.size());
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.execute.parallel.used"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.trainingRagProbe.enabled"));
        assertEquals(2, TraceStore.get("extremez.trainingRagProbe.returnedCount"));
        assertEquals(2, TraceStore.get("extremeZ.sequential.trainingRagCount"));
        verify(trainingService, times(2)).findTrainingRagSamples(anyString(), anyInt());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("RAG evidence official"));
    }

    private static ExtremeZTrigger.Decision activeExtremeZDecision() {
        return new ExtremeZTrigger.Decision(
                true,
                true,
                false,
                false,
                false,
                "lowRecall",
                new ExecutionPlan(
                        ExecutionPlan.PrimaryMode.EXTREMEZ,
                        true,
                        false,
                        false,
                        List.of("lowRecall"),
                        ExecutionPlan.DEFAULT_STAGES,
                        Map.of()));
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        int lastTimedInvokeAllTaskCount;

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public <T> List<Future<T>> invokeAll(
                Collection<? extends Callable<T>> tasks,
                long timeout,
                TimeUnit unit) {
            lastTimedInvokeAllTaskCount = tasks.size();
            return tasks.stream()
                    .map(task -> {
                        FutureTask<T> future = new FutureTask<>(task);
                        future.run();
                        return (Future<T>) future;
                    })
                    .toList();
        }
    }
}
