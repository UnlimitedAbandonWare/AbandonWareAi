package com.example.lms.service.understanding;

import com.example.lms.jobs.JobService;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A derived summary handler. It cannot create an origin chat run or emit session SSE. */
@Component
public class DeferredUnderstandingJobHandler implements JobService.DerivedJobHandler {
    private final JobService jobs;
    private final UnderstandingCommitService commits;
    private final AnswerUnderstandingService understanding;
    private final ObjectMapper mapper;
    @Value("${abandonware.understanding.deferred.enabled:false}")
    private boolean enabled;
    public DeferredUnderstandingJobHandler(JobService jobs, UnderstandingCommitService commits,
            AnswerUnderstandingService understanding, ObjectMapper mapper) {
        this.jobs = jobs; this.commits = commits; this.understanding = understanding; this.mapper = mapper;
    }
    @PostConstruct public void register() {
        if (enabled && jobs.runsPersistedJobs() && !jobs.isTypeDisabled(JobService.UNDERSTANDING_TYPE))
            jobs.registerDerivedHandler(JobService.UNDERSTANDING_TYPE, this);
    }
    private DeferredUnderstandingTask task(String json) throws Exception {
        return mapper.readValue(json, DeferredUnderstandingTask.class);
    }
    @Override public boolean recover(JobService.DerivedClaim claim, String payload) throws Exception {
        return commits.recover(claim, task(payload));
    }
    @Override public String compute(String payload) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive() || ChatRunExecutionContext.current() != null)
            throw new IllegalStateException("understanding_compute_context_invalid");
        var task = task(payload);
        var pair = commits.sourceForCompute(task);
        try {
            // modelId pins the existing understanding configuration; provider routing stays with GeminiClient.
            var outcome = understanding.understandDerived(pair.assistant().text(), pair.user().text(),
                    task.budgetMillis(), task.modelId());
            if (outcome == null || outcome.kind() == AnswerUnderstandingService.OutcomeKind.FAILURE)
                throw new IllegalStateException("understanding_compute_failed");
            if (outcome.kind() == AnswerUnderstandingService.OutcomeKind.SKIPPED || outcome.value() == null)
                throw new JobService.DerivedRejected();
            return mapper.writeValueAsString(outcome);
        } finally { com.example.lms.search.TraceStore.clear(); }
    }
    @Override public void commit(JobService.DerivedClaim claim, String payload, String prepared) throws Exception {
        commits.commit(claim, task(payload), mapper.readValue(prepared, AnswerUnderstandingService.Outcome.class));
    }
}
