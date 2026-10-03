package com.example.lms.service.understanding;

import com.example.lms.jobs.JobService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor;
import com.example.lms.service.rag.graph.GeneralGraphSourceAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.util.function.Function;

/** UNDERSTANDING T3 uses the established source owner and the same chat/job transaction. */
@Service
public class UnderstandingCommitService {
    @org.springframework.beans.factory.annotation.Value("${abandonware.understanding.deferred.enabled:false}")
    private boolean deferredEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    private AnswerUnderstandingService understanding;
    @org.springframework.beans.factory.annotation.Autowired
    private UnderstandAndMemorizeInterceptor legacy;
    private final JobService jobs;
    private final GeneralGraphSourceAuthority sources;
    private final ChatHistoryService history;
    private final UnderstandingReceiptRepository receipts;
    private final MemoryReinforcementService memory;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    public UnderstandingCommitService(JobService jobs, GeneralGraphSourceAuthority sources, ChatHistoryService history,
            UnderstandingReceiptRepository receipts, MemoryReinforcementService memory, ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.jobs = jobs; this.sources = sources; this.history = history;
        this.receipts = receipts; this.memory = memory; this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Request-local approval; never serialized to a job, trace, or public response. */
    public record Intent(com.example.lms.service.rag.graph.GeneralGraphScope scope,
            com.example.lms.assist.MemoryEvidence user, long userId, String originalRunId,
            String question, String answer, com.example.lms.guard.GuardProfile guardProfile,
            com.example.lms.domain.enums.MemoryMode memoryMode, String modelId, long budgetMillis) {
        @Override public String toString() { return "UnderstandingIntent[redacted]"; }
    }
    public record TranscriptPlan(Intent intent, boolean deferred, com.example.lms.dto.answer.AnswerUnderstanding fallback) {
        @Override public String toString() { return "UnderstandingTranscriptPlan[redacted]"; }
    }

    /** All eligibility checks precede skipping F01-A. This method never calls a provider. */
    public boolean stageDeferred(com.example.lms.service.chat.ChatRunExecutionContext run,
            com.example.lms.service.rag.graph.GeneralGraphScope scope, String question, String approvedAnswer,
            com.example.lms.guard.GuardProfile profile, com.example.lms.domain.enums.MemoryMode mode,
            boolean memorySaveAllowed, boolean requestEnabled) {
        if (!deferredEnabled || understanding == null || !understanding.isEnabled() || !requestEnabled
                || !memorySaveAllowed || !memory.understandingMemoryApproved()
                || profile == null || profile == com.example.lms.guard.GuardProfile.PROFILE_FREE
                || profile == com.example.lms.guard.GuardProfile.WILD
                || mode != com.example.lms.domain.enums.MemoryMode.FULL
                || scope == null || !scope.memoryEnabled() || run == null || !run.belongsToSession(scope.sessionId())
                || run.persistedUserMessageId() == null || question == null || approvedAnswer == null
                || approvedAnswer.isBlank() || !jobs.derivedReady(JobService.UNDERSTANDING_TYPE)) return false;
        long userId = run.persistedUserMessageId();
        var source = transactions.execute(tx -> sources.source(scope, userId));
        if (source == null || source.isEmpty() || !"USER".equals(source.get().sourceRole())
                || !question.equals(source.get().text())) return false;
        String model = understanding.configuredModelId();
        long budget = understanding.configuredBudgetMillis();
        if (model == null || model.isBlank() || budget <= 0) return false;
        return run.rememberUnderstandingIntent(new Intent(scope, source.get(), userId, run.clientToken(),
                question, approvedAnswer, profile, mode, model, budget));
    }

    /** Called before the transcript gate. A mapping/store failure may fall back only before admission. */
    public TranscriptPlan prepareTranscript(com.example.lms.service.chat.ChatRunExecutionContext run,
            String persistedAnswer, boolean held) {
        Intent intent = run == null ? null : run.understandingIntent().orElse(null);
        if (intent == null) return null;
        if (held) return new TranscriptPlan(intent, false, null);
        if (intent.answer().equals(persistedAnswer) && jobs.derivedReady(JobService.UNDERSTANDING_TYPE))
            return new TranscriptPlan(intent, true, null);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("understanding_fallback_compute_in_transaction");
        return new TranscriptPlan(intent, false, legacy.prepare(intent.question(), intent.answer(), true));
    }

    /** T0: assistant and durable intent commit together; any admission failure rolls back the assistant. */
    public Long persistOrigin(com.example.lms.service.chat.ChatRunExecutionContext run, long sessionId,
            String persistedAnswer, TranscriptPlan plan) {
        if (plan == null) return history.appendMessageReturningId(sessionId, "assistant", persistedAnswer);
        Intent intent = plan.intent();
        if (run == null || !run.belongsToSession(sessionId) || !intent.originalRunId().equals(run.clientToken())
                || run.understandingIntent().orElse(null) != intent)
            throw new JobService.DerivedRejected();
        if (!plan.deferred()) {
            Long id = history.appendMessageReturningId(sessionId, "assistant", persistedAnswer);
            legacy.commitPrepared("chat-" + sessionId, intent.question(), plan.fallback(), run);
            return id;
        }
        if (!intent.answer().equals(persistedAnswer)) throw new JobService.DerivedRejected();
        return transactions.execute(tx -> sources.withCurrentSource(intent.scope(), intent.user(), current -> {
            if (!"USER".equals(current.sourceRole()) || !intent.question().equals(current.text()))
                throw new JobService.DerivedRejected();
            Long assistantId = history.appendMessageStrictReturningId(sessionId, "assistant", persistedAnswer);
            if (assistantId == null) throw new IllegalStateException("understanding_origin_unconfirmed");
            var assistant = sources.source(intent.scope(), assistantId).orElseThrow(JobService.DerivedRejected::new);
            if (!"ASSISTANT".equals(assistant.sourceRole()) || !persistedAnswer.equals(assistant.text()))
                throw new JobService.DerivedRejected();
            var task = new DeferredUnderstandingTask(1, intent.originalRunId(), intent.scope().ownerNamespace(),
                    sessionId, intent.scope().channel(), intent.scope().consentEpoch(), intent.userId(),
                    current.sourceRevision(), assistantId, assistant.sourceRevision(), "UNDERSTANDING",
                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(intent.question()),
                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(intent.answer()),
                    intent.guardProfile(), intent.memoryMode(), true, true, true, intent.modelId(), intent.budgetMillis());
            jobs.enqueueDerivedOnce(task, task.identity(), task.admissionKey(), task.requestFingerprint());
            return assistantId;
        }).orElseThrow(JobService.DerivedRejected::new));
    }
    private <T> T withSources(DeferredUnderstandingTask task, Function<GeneralGraphSourceAuthority.SourcePair,T> action) {
        return sources.withCurrentSourcePair(task.ownerNamespace(), task.sessionId(), task.consentEpoch(),
                task.userMessageId(), task.userRevision(), task.assistantMessageId(), task.assistantRevision(), pair -> {
                    if (!task.approves(pair.user().text(), pair.assistant().text())) throw new JobService.DerivedRejected();
                    return action.apply(pair);
                }).orElseThrow(JobService.DerivedRejected::new);
    }
    /** The returned immutable text is used after this short validation transaction has ended. */
    public GeneralGraphSourceAuthority.SourcePair sourceForCompute(DeferredUnderstandingTask task) {
        return transactions.execute(tx -> withSources(task, pair -> pair));
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("understanding_commit_transaction_required");
    }
    public boolean cancelRun(String ownerNamespace, long sessionId, String originalRunId) {
        return jobs.cancelDerivedRun(ownerNamespace, sessionId, originalRunId);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recover(JobService.DerivedClaim claim, DeferredUnderstandingTask task) {
        requireTransaction();
        return withSources(task, pair -> {
            jobs.requireDerivedLease(claim); // session -> both messages -> job -> receipt
            if (receipts.findPersisted(task).isEmpty()) return false;
            jobs.completeDerived(claim);
            return true;
        });
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void commit(JobService.DerivedClaim claim, DeferredUnderstandingTask task, AnswerUnderstandingService.Outcome outcome) {
        requireTransaction();
        if (outcome == null || outcome.value() == null
                || !(outcome.kind() == AnswerUnderstandingService.OutcomeKind.PROVIDER
                    || outcome.kind() == AnswerUnderstandingService.OutcomeKind.HEURISTIC_FALLBACK)
                || UnderstandAndMemorizeInterceptor.renderForMemory(outcome.value()).isBlank())
            throw new JobService.DerivedRejected();
        final String json;
        try { json = mapper.writeValueAsString(outcome.value()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new JobService.DerivedRejected(); }
        withSources(task, pair -> {
            jobs.requireDerivedLease(claim);
            if (receipts.findPersisted(task).isPresent()) {
                jobs.completeDerived(claim);
                return true;
            }
            Long usumId = history.appendMessageStrictReturningId(task.sessionId(), "system",
                    UnderstandAndMemorizeInterceptor.USUM_META_PREFIX + json);
            if (usumId == null) throw new IllegalStateException("understanding_usum_unconfirmed");
            receipts.insert(task, claim, usumId, json);
            memory.reinforceUnderstandingLocal(task, pair.user().text(),
                    UnderstandAndMemorizeInterceptor.renderForMemory(outcome.value()), outcome.value().confidence());
            jobs.completeDerived(claim);
            return true;
        });
    }
}
