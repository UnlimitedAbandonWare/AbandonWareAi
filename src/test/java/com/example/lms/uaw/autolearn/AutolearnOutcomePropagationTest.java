package com.example.lms.uaw.autolearn;

import com.example.lms.uaw.autolearn.ingest.TrainRagIngestService;
import com.example.lms.uaw.presence.UserAbsenceGate;
import com.example.lms.uaw.orchestration.UawOrchestrationGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutolearnOutcomePropagationTest {
    @TempDir Path temp;
    @Test void incompleteIngestCannotResetBudgetFailureBackoff() {
        var props=new UawAutolearnProperties(); var state=temp.resolve("budget.json");
        props.getBudget().setStatePath(state.toString()); props.getBudget().setMinIntervalSeconds(0); props.getBudget().setMaxRunsPerDay(10);
        props.getRetrain().setMinAcceptedToTrain(1); props.getIdleTrigger().setBreadcrumbEnabled(false);
        props.getDataset().setPath(temp.resolve("dataset.jsonl").toString());
        var presence=mock(UserAbsenceGate.class); when(presence.isUserAbsentNow()).thenReturn(true);
        var gate=new UawOrchestrationGate(presence) {
            @Override public Decision decide(String stage,double threshold,String... breakers) {return new Decision(true,"ok",0.1);}
        };
        var learn=mock(UawAutolearnService.class);
        when(learn.runCycle(any(),anyString(),any(),anyLong())).thenReturn(new AutoLearnCycleResult(2,1,false,props.getDataset().getPath()));
        var ingest=mock(TrainRagIngestService.class);
        when(ingest.ingestNewSamplesDetailed(any(),anyString(),any())).thenReturn(
                new TrainRagIngestService.IngestOutcome(0,0,1,0,0,"store_failure","vector_flush",true,false));
        var budget=new AutoLearnBudgetManager(props,new AutoLearnRunStateStore());
        new UawAutolearnOrchestrator(new MockEnvironment().withProperty("uaw.autolearn.enabled","true")
                .withProperty("uaw.autolearn.idle-trigger.enabled","true"),props,presence,gate,learn,
                new AutolearnRagRetrainOrchestrator(ingest,props),budget,mock(UawAutolearnQualityTracker.class)).tick();
        var result=new AutoLearnRunStateStore().load(state,LocalDate.now().toString());
        assertEquals(1,result.consecutiveFailures); assertTrue(result.backoffUntilEpochMs>0);
    }
}
