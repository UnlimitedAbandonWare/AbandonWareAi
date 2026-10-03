package com.example.lms.jobs;

import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared-store task execution. No automatic schema mutation or memory fallback. */
public final class JdbcJobService implements JobService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JdbcJobService.class);
    private static final long RETENTION_MS = Duration.ofHours(24).toMillis();
    private static final long LEASE_MS = 30_000;
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final int MAX_RESULT_BYTES = 4_194_304;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Set<String> enabledTypes;
    private final Set<String> callbackEnabledTypes;
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, DerivedJobHandler> derivedHandlers = new ConcurrentHashMap<>();
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean(), storeFailure = new AtomicBoolean();
    private final Semaphore slots = new Semaphore(4);
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> daemon(r, "durable-job-worker"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> daemon(r, "durable-job-clock"));

    public JdbcJobService(DataSource dataSource, ObjectMapper mapper, Clock clock,
            PlatformTransactionManager transactionManager, Set<String> enabledTypes,
            Set<String> callbackEnabledTypes) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
        this.jdbc.setQueryTimeout(5);
        Objects.requireNonNull(transactionManager);
        DataSource managedDataSource;
        if (transactionManager instanceof JpaTransactionManager jpa) managedDataSource = jpa.getDataSource();
        else if (transactionManager instanceof DataSourceTransactionManager sql) managedDataSource = sql.getDataSource();
        else throw new IllegalArgumentException("job_transaction_manager_unverified");
        if (managedDataSource != dataSource) throw new IllegalArgumentException("job_transaction_datasource_mismatch");
        this.enabledTypes = Set.copyOf(enabledTypes);
        this.callbackEnabledTypes = Set.copyOf(callbackEnabledTypes);
        if (this.enabledTypes.contains("understanding_summary_v1")
                && !(transactionManager instanceof JpaTransactionManager))
            throw new IllegalArgumentException("understanding_shared_jpa_transaction_required");
        if (!this.enabledTypes.containsAll(this.callbackEnabledTypes)
                || this.callbackEnabledTypes.contains("understanding_summary_v1"))
            throw new IllegalArgumentException("job_callback_type_disabled");
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public void registerHandler(String type, JobHandler handler) {
        if (UNDERSTANDING_TYPE.equals(type)) throw new IllegalArgumentException("derived_handler_required");
        if (isTypeDisabled(type)) return;
        if (handlers.putIfAbsent(checked(type, 64), Objects.requireNonNull(handler)) != null)
            throw new IllegalStateException("duplicate_job_handler");
    }
    @Override public boolean runsPersistedJobs() { return true; }
    @Override public boolean isTypeDisabled(String type) { return !enabledTypes.contains(type); }
    private void requireEnabled(String type) {
        if (isTypeDisabled(type)) throw new RejectedExecutionException("job_type_disabled");
    }
    @Override public String enqueue(String payload) {
        return enqueue("legacy", Objects.requireNonNull(payload), Map.of(), null);
    }
    @Override public String enqueue(String type, Object payload, Map<String, Object> metadata, String correlationId) {
        requireEnabled(type);
        if (closed.get()) throw new RejectedExecutionException("job_service_closed");
        String json;
        try { json = mapper.writeValueAsString(payload); }
        catch (Exception invalid) { throw new IllegalArgumentException("invalid_job_payload"); }
        requireSize(json, MAX_PAYLOAD_BYTES);
        String owner = metadata == null ? null : Objects.toString(metadata.get("ownerHash"), null);
        if (owner == null) owner = org.apache.commons.codec.digest.DigestUtils.sha256Hex("system-job");
        String id = UUID.randomUUID().toString(); long now = clock.millis();
        jdbc.update("INSERT INTO awx_jobs(task_id,job_type,owner_hash,payload,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                id, checked(type, 64), checked(owner, 128), json, "PENDING", now, now);
        return id;
    }
    @Override public <T> void executeAsync(String id, Supplier<T> work, Consumer<T> success) {
        throw new IllegalStateException("persisted_job_handler_required");
    }
    @Override public Optional<Admission> findAdmission(String type,String owner,String key,String fingerprint){
        requireEnabled(type);
        String identity=admissionIdentity(type,owner,key,fingerprint);
        return jdbc.query("SELECT task_id,state,request_fingerprint,expires_at FROM awx_jobs WHERE admission_key=? AND owner_hash=? AND job_type=?",
                (rs,row)->new AdmissionRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4)==null?null:rs.getLong(4)),identity,owner,type).stream()
                .filter(row->row.expires()==null||row.expires()>clock.millis()).findFirst().map(row->{if(!fingerprint.equals(row.fingerprint()))throw new IdempotencyConflict();return new Admission(row.id(),true,row.state());});
    }
    @Override public Admission enqueueOnce(String type,Object payload,Map<String,Object> metadata,String correlationId,String key,String fingerprint){
        requireEnabled(type);
        if(closed.get())throw new RejectedExecutionException("job_service_closed");
        String owner=metadata==null?null:Objects.toString(metadata.get("ownerHash"),null),identity=admissionIdentity(type,owner,key,fingerprint);
        var existing=findAdmission(type,owner,key,fingerprint);if(existing.isPresent())return existing.get();
        String json;try{json=mapper.writeValueAsString(payload);}catch(Exception invalid){throw new IllegalArgumentException("invalid_job_payload");}requireSize(json,MAX_PAYLOAD_BYTES);
        long now=clock.millis();
        // Only terminal rows have expires_at. Never reclaim a pending/running/unknown execution.
        transactions.executeWithoutResult(tx->{
            jdbc.update("DELETE FROM awx_job_results WHERE task_id IN (SELECT task_id FROM awx_jobs WHERE admission_key=? AND expires_at<=?)",identity,now);
            jdbc.update("DELETE FROM awx_jobs WHERE admission_key=? AND expires_at<=?",identity,now);
        });
        String id=UUID.randomUUID().toString();
        try{jdbc.update("INSERT INTO awx_jobs(task_id,job_type,owner_hash,payload,state,created_at,updated_at,admission_key,request_fingerprint) VALUES(?,?,?,?,'PENDING',?,?,?,?)",id,type,owner,json,now,now,identity,fingerprint);return new Admission(id,false,"PENDING");}
        catch(org.springframework.dao.DuplicateKeyException collision){return findAdmission(type,owner,key,fingerprint).orElseThrow(()->collision);}
    }
    private static String admissionIdentity(String type,String owner,String key,String fingerprint){
        if(type==null||!type.matches("[A-Za-z0-9_.-]{1,64}")||owner==null||!owner.matches("[A-Za-z0-9_-]{1,128}")||key==null||!key.matches("[A-Za-z0-9._:-]{1,128}")||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid_job_admission");
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(owner+"\n"+type+"\n"+key);
    }


    @Override public void registerDerivedHandler(String type, DerivedJobHandler handler) {
        if (!UNDERSTANDING_TYPE.equals(type)) throw new IllegalArgumentException("unsupported_derived_type");
        if (isTypeDisabled(type)) return;
        if (derivedHandlers.putIfAbsent(type, Objects.requireNonNull(handler)) != null)
            throw new IllegalStateException("duplicate_derived_handler");
    }

    @Override public boolean derivedReady(String type) {
        if (!UNDERSTANDING_TYPE.equals(type) || isTypeDisabled(type) || !derivedHandlers.containsKey(type) || closed.get()) return false;
        try {
            jdbc.queryForList("SELECT effect_key,original_run_id,source_session_id,phase,compute_attempts,commit_attempts,next_attempt_at,admission_key,request_fingerprint FROM awx_jobs WHERE 1=0");
            jdbc.queryForList("SELECT effect_key,owner_namespace,session_id,channel,consent_epoch,user_message_id,user_revision,assistant_message_id,assistant_revision,kind,original_run_id,job_task_id,usum_message_id,result_sha256,persisted_at,receipt_state FROM awx_understanding_receipts WHERE 1=0");
            return true;
        } catch (org.springframework.dao.DataAccessException unavailable) { return false; }
    }

    @Override public Admission enqueueDerivedOnce(Object payload, DerivedIdentity identity, String key, String fingerprint) {
        requireTransaction();
        if (!derivedReady(UNDERSTANDING_TYPE)) throw new IllegalStateException("derived_store_not_ready");
        if (identity == null || identity.ownerHash() == null || !identity.ownerHash().matches("[a-f0-9]{64}")
                || identity.sessionId() <= 0 || identity.originalRunId() == null
                || !identity.originalRunId().matches("[A-Za-z0-9_-]{1,64}")
                || identity.effectKey() == null || !identity.effectKey().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid_derived_identity");
        Admission admission = enqueueOnce(UNDERSTANDING_TYPE, payload, Map.of("ownerHash", identity.ownerHash()),
                null, key, fingerprint);
        if (!admission.replayed()) {
            jdbc.update("UPDATE awx_jobs SET effect_key=?,original_run_id=?,source_session_id=?,phase='COMPUTE' WHERE task_id=?",
                    identity.effectKey(), identity.originalRunId(), identity.sessionId(), admission.taskId());
        } else {
            String json;
            try { json = mapper.writeValueAsString(payload); }
            catch (Exception invalid) { throw new IllegalArgumentException("invalid_job_payload"); }
            // A matching digest is not proof that the approved tuple/payload matches.
            var exact = jdbc.queryForObject("SELECT COUNT(*) FROM awx_jobs WHERE task_id=? AND owner_hash=? AND effect_key=? AND original_run_id=? AND source_session_id=? AND payload=?",
                    Integer.class, admission.taskId(), identity.ownerHash(), identity.effectKey(), identity.originalRunId(), identity.sessionId(), json);
            if (exact == null || exact != 1) throw new IdempotencyConflict();
        }
        return admission;
    }

    private static void requireTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("derived_transaction_required");
    }

    @Override public void requireDerivedLease(DerivedClaim claim) {
        requireTransaction();
        var valid = jdbc.query("SELECT state,worker_token,lease_until FROM awx_jobs WHERE task_id=? AND job_type=? FOR UPDATE",
                (rs, row) -> "RUNNING".equals(rs.getString(1)) && claim.token().equals(rs.getString(2))
                        && rs.getLong(3) > clock.millis(), claim.taskId(), UNDERSTANDING_TYPE);
        if (valid.isEmpty() || !valid.get(0)) throw new IllegalStateException("derived_lease_lost");
    }

    @Override public void completeDerived(DerivedClaim claim) {
        requireDerivedLease(claim);
        long now = clock.millis();
        if (jdbc.update("UPDATE awx_jobs SET state='SUCCEEDED',phase='DONE',completed_at=?,expires_at=?,updated_at=?,error_code=NULL,callback_state='NONE' WHERE task_id=? AND state='RUNNING' AND worker_token=? AND lease_until>?",
                now, now + RETENTION_MS, now, claim.taskId(), claim.token(), now) != 1)
            throw new IllegalStateException("derived_lease_lost");
    }

    /** T1 claim is short; T2 checkpoints compute; only the typed source transaction may perform T3. */
    private boolean runDerivedOnce() {
        DerivedJobHandler handler = derivedHandlers.get(UNDERSTANDING_TYPE);
        if (handler == null || !derivedReady(UNDERSTANDING_TYPE)) return false;
        long now = clock.millis();
        var candidates = jdbc.query("SELECT j.task_id,j.payload,j.phase,r.body FROM awx_jobs j LEFT JOIN awx_job_results r ON r.result_id=j.result_ref WHERE j.job_type=? AND j.state IN ('PENDING','OUTCOME_UNKNOWN') AND j.phase IN ('COMPUTE','COMMIT') AND j.next_attempt_at<=? AND (j.error_code IS NULL OR j.error_code<>'cancelled_worker_lost') AND ((j.phase='COMPUTE' AND j.compute_attempts<2) OR (j.phase='COMMIT' AND j.commit_attempts<3)) ORDER BY j.created_at,j.task_id LIMIT 4",
                (rs, i) -> new DerivedWork(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), UNDERSTANDING_TYPE, now);
        for (DerivedWork work : candidates) {
            if (running.containsKey(work.id())) continue;
            var claim = new DerivedClaim(work.id(), UUID.randomUUID().toString());
            String attempt = "COMMIT".equals(work.phase()) ? "commit_attempts" : "compute_attempts";
            int changed = jdbc.update("UPDATE awx_jobs SET state='RUNNING',worker_token=?,lease_until=?,updated_at=?," + attempt + "=" + attempt + "+1 WHERE task_id=? AND state IN ('PENDING','OUTCOME_UNKNOWN') AND phase=? AND next_attempt_at<=? AND " + attempt + "<? AND (error_code IS NULL OR error_code<>'cancelled_worker_lost')",
                    claim.token(), now + LEASE_MS, now, work.id(), work.phase(), now, "COMMIT".equals(work.phase()) ? 3 : 2);
            if (changed != 1) continue;
            executeDerived(work, claim, handler);
            return true;
        }
        return false;
    }

    private void executeDerived(DerivedWork work, DerivedClaim claim, DerivedJobHandler handler) {
        Running own = new Running(claim.token(), Thread.currentThread());
        running.put(work.id(), own);
        try {
            if (closed.get()) return;
            // Receipt recovery always precedes provider work or prepared-result replay.
            boolean recovered = Boolean.TRUE.equals(transactions.execute(tx -> {
                var active = jdbc.query("SELECT state,worker_token,lease_until FROM awx_jobs WHERE task_id=? AND job_type=?",
                        (rs, row) -> "RUNNING".equals(rs.getString(1)) && claim.token().equals(rs.getString(2))
                                && rs.getLong(3) > clock.millis(), claim.taskId(), UNDERSTANDING_TYPE);
                if (active.isEmpty() || !active.get(0)) throw new IllegalStateException("derived_lease_lost");
                boolean found;
                try { found = handler.recover(claim, work.payload()); }
                catch (RuntimeException failure) { throw failure; }
                catch (Exception failure) { throw new IllegalStateException("derived_recovery_failed", failure); }
                if (found) requireDerivedCompletion(claim);
                else tx.setRollbackOnly(); // A negative receipt probe cannot commit incidental writes.
                return found;
            }));
            if (!recovered) {
                String prepared = work.body();
                if ("COMPUTE".equals(work.phase())) {
                    prepared = Objects.requireNonNull(handler.compute(work.payload()), "derived_result_missing");
                    requireSize(prepared, MAX_RESULT_BYTES);
                    String body = prepared, resultId = UUID.randomUUID().toString();
                    transactions.executeWithoutResult(tx -> {
                        requireDerivedLease(claim);
                        jdbc.update("INSERT INTO awx_job_results(result_id,task_id,body,body_sha256,body_bytes) VALUES(?,?,?,?,?)",
                                resultId, work.id(), body, org.apache.commons.codec.digest.DigestUtils.sha256Hex(body), body.getBytes(StandardCharsets.UTF_8).length);
                        jdbc.update("UPDATE awx_jobs SET result_ref=?,phase='COMMIT',commit_attempts=commit_attempts+1,updated_at=? WHERE task_id=?",
                                resultId, clock.millis(), work.id());
                    });
                }
                if (prepared == null) throw new DerivedRejected();
                String body = prepared;
                transactions.executeWithoutResult(tx -> {
                    try { handler.commit(claim, work.payload(), body); }
                    catch (RuntimeException failure) { throw failure; }
                    catch (Exception failure) { throw new IllegalStateException("derived_commit_failed", failure); }
                    requireDerivedCompletion(claim);
                });
            }
        } catch (Exception failure) {
            long now = clock.millis();
            if (failure instanceof DerivedRejected) {
                jdbc.update("UPDATE awx_jobs SET state='FAILED',error_code='derived_source_or_policy_invalidated',completed_at=?,expires_at=?,updated_at=? WHERE task_id=? AND state='RUNNING' AND worker_token=? AND lease_until>?",
                        now, now + RETENTION_MS, now, work.id(), claim.token(), now);
            } else {
                jdbc.update("UPDATE awx_jobs SET state='OUTCOME_UNKNOWN',error_code='derived_outcome_unconfirmed',next_attempt_at=?,updated_at=? WHERE task_id=? AND state='RUNNING' AND worker_token=? AND lease_until>?",
                        now + 1000, now, work.id(), claim.token(), now);
            }
        } finally {
            long now = clock.millis();
            try {
                // A cancellation acknowledgement never substitutes for physical handler return.
                jdbc.update("UPDATE awx_jobs SET state='CANCELLED',completed_at=?,expires_at=?,updated_at=? WHERE task_id=? AND (state='CANCEL_REQUESTED' OR (state='OUTCOME_UNKNOWN' AND error_code='cancelled_worker_lost')) AND worker_token=?",
                        now, now + RETENTION_MS, now, work.id(), claim.token());
            } finally { releaseWorker(work.id(), own); }
        }
    }

    /** End-of-T3 fence is compulsory even when an adapter forgets its pre-write guard. */
    private void requireDerivedCompletion(DerivedClaim claim) {
        requireTransaction();
        var confirmed = jdbc.query("SELECT state,worker_token,lease_until FROM awx_jobs WHERE task_id=? AND job_type=? FOR UPDATE",
                (rs, row) -> "SUCCEEDED".equals(rs.getString(1)) && claim.token().equals(rs.getString(2))
                        && rs.getLong(3) > clock.millis(), claim.taskId(), UNDERSTANDING_TYPE);
        if (confirmed.isEmpty() || !confirmed.get(0)) throw new IllegalStateException("derived_commit_unconfirmed");
    }

    @Override public boolean cancelDerivedRun(String owner, long sessionId, String runId) {
        if (isTypeDisabled(UNDERSTANDING_TYPE) || owner == null || runId == null) return false;
        long now = clock.millis();
        int pending = jdbc.update("UPDATE awx_jobs SET state='CANCELLED',error_code='derived_cancelled',completed_at=?,expires_at=?,updated_at=? WHERE job_type=? AND owner_hash=? AND source_session_id=? AND original_run_id=? AND state='PENDING'",
                now, now + RETENTION_MS, now, UNDERSTANDING_TYPE, owner, sessionId, runId);
        // A lost lease does not prove that the previous provider slot returned.
        int unknown = jdbc.update("UPDATE awx_jobs SET error_code='cancelled_worker_lost',updated_at=? WHERE job_type=? AND owner_hash=? AND source_session_id=? AND original_run_id=? AND state='OUTCOME_UNKNOWN'",
                now, UNDERSTANDING_TYPE, owner, sessionId, runId);
        int active = jdbc.update("UPDATE awx_jobs SET state='CANCEL_REQUESTED',error_code='derived_cancelled',updated_at=? WHERE job_type=? AND owner_hash=? AND source_session_id=? AND original_run_id=? AND state='RUNNING'",
                now, UNDERSTANDING_TYPE, owner, sessionId, runId);
        if (active + unknown > 0) {
            var ids = jdbc.queryForList("SELECT task_id FROM awx_jobs WHERE job_type=? AND owner_hash=? AND source_session_id=? AND original_run_id=? AND (state='CANCEL_REQUESTED' OR (state='OUTCOME_UNKNOWN' AND error_code='cancelled_worker_lost'))",
                    String.class, UNDERSTANDING_TYPE, owner, sessionId, runId);
            for (String id : ids) interruptIfCurrent(id, running.get(id));
        }
        return pending + active + unknown > 0;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabledTypes.isEmpty() || closed.get() || !started.compareAndSet(false, true)) return;
        scheduler.scheduleWithFixedDelay(() -> guarded(this::maintenance), 0, 500, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            if (closed.get() || !slots.tryAcquire()) return;
            try { workers.execute(() -> { try { guarded(this::runPendingOnce); } finally { slots.release(); } }); }
            catch (RejectedExecutionException rejected) { slots.release(); }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    /** Bounded one-job poll; separately callable for deterministic recovery tests. */
    public void runPendingOnce() {
        if (closed.get() || enabledTypes.isEmpty()) return;
        if (runDerivedOnce()) return;
        deliverOneCallback();
        for (var entry : handlers.entrySet()) {
            if (isTypeDisabled(entry.getKey())) continue;
            var rows = jdbc.query("SELECT task_id,payload FROM awx_jobs WHERE state='PENDING' AND job_type=? ORDER BY created_at,task_id LIMIT 1",
                    (rs, i) -> new Work(rs.getString(1), rs.getString(2)), entry.getKey());
            if (rows.isEmpty()) continue;
            Work work = rows.get(0); String token = UUID.randomUUID().toString(); long now = clock.millis();
            if (jdbc.update("UPDATE awx_jobs SET state='RUNNING',worker_token=?,lease_until=?,updated_at=? WHERE task_id=? AND state='PENDING'",
                    token, now + LEASE_MS, now, work.id()) != 1) continue;
            execute(work, token, entry.getKey(), entry.getValue());
            return;
        }
    }

    private void execute(Work work, String token, String type, JobHandler handler) {
        Running own = new Running(token, Thread.currentThread(), "task_ask".equals(type));
        running.put(work.id(), own);
        try {
            if (closed.get()) return;
            String body = Objects.requireNonNull(handler.execute(work.payload()));
            requireSize(body, MAX_RESULT_BYTES);
            boolean callback = callbackEnabledTypes.contains(type) && handler.needsCompletion(work.payload());
            String resultId = UUID.randomUUID().toString(); long now = clock.millis();
            transactions.executeWithoutResult(tx -> {
                if (own.acceptedTask() && !renewLiveTaskOwner(work.id(), own)) return;
                int changed = jdbc.update("UPDATE awx_jobs SET state='SUCCEEDED',result_ref=?,completed_at=?,expires_at=?,updated_at=?,callback_state=?,callback_next=? WHERE task_id=? AND state='RUNNING' AND worker_token=? AND lease_until>?",
                        resultId, now, now + RETENTION_MS, now, callback ? "PENDING" : "NONE", now, work.id(), token, now);
                if (changed == 1) jdbc.update("INSERT INTO awx_job_results(result_id,task_id,body,body_sha256,body_bytes) VALUES(?,?,?,?,?)",
                        resultId, work.id(), body, org.apache.commons.codec.digest.DigestUtils.sha256Hex(body), body.getBytes(StandardCharsets.UTF_8).length);
            });
        } catch (Exception failure) {
            long now = clock.millis();
            jdbc.update("UPDATE awx_jobs SET state='OUTCOME_UNKNOWN',error_code='execution_outcome_unconfirmed',updated_at=? WHERE task_id=? AND state='RUNNING' AND worker_token=? AND lease_until>?",
                    now, work.id(), token, now);
            log.info("[JOB_OUTCOME_UNKNOWN] taskHash={} reason=execution_outcome_unconfirmed", SafeRedactor.hashValue(work.id()));
        } finally {
            long now = clock.millis();
            try {
                jdbc.update("UPDATE awx_jobs SET state='CANCELLED',completed_at=?,expires_at=?,updated_at=? WHERE task_id=? AND (state='CANCEL_REQUESTED' OR (job_type='task_ask' AND state='OUTCOME_UNKNOWN' AND error_code='cancelled_worker_lost')) AND worker_token=?",
                        now, now + RETENTION_MS, now, work.id(), token);
            } finally { releaseWorker(work.id(), own); }
        }
    }

    private void interruptIfCurrent(String id, Running expected) {
        synchronized (running) {
            if (expected != null && running.get(id) == expected) expected.thread().interrupt();
        }
    }
    private void releaseWorker(String id, Running expected) {
        synchronized (running) {
            running.remove(id, expected);
            Thread.interrupted();
        }
    }
    /** Called inside a transaction; only a current task_ask worker may refresh its own authority. */
    private boolean renewLiveTaskOwner(String id, Running own) {
        if (!isCurrentWorker(id, own)) return false;
        var observations = jdbc.query(
                "SELECT state,worker_token,error_code FROM awx_jobs WHERE task_id=? AND job_type='task_ask' FOR UPDATE",
                (rs, row) -> new TaskLeaseObservation(rs.getString(1), rs.getString(2), rs.getString(3)), id);
        if (observations.isEmpty()) return false; // Missing store proof is unknown ownership.
        TaskLeaseObservation observed = observations.get(0);
        boolean sameToken = own.token().equals(observed.token());
        boolean replacement = observed.token() != null && !observed.token().isBlank() && !sameToken;
        boolean cancelled = sameToken && ("CANCEL_REQUESTED".equals(observed.state())
                || "CANCELLED".equals(observed.state())
                || ("OUTCOME_UNKNOWN".equals(observed.state())
                    && "cancelled_worker_lost".equals(observed.error())));
        if (replacement || cancelled) {
            if (Thread.currentThread() != own.thread()) interruptIfCurrent(id, own);
            return false;
        }
        boolean expiryOnly = "OUTCOME_UNKNOWN".equals(observed.state())
                && "worker_lost".equals(observed.error());
        if (!sameToken || (!"RUNNING".equals(observed.state()) && !expiryOnly)
                || !isCurrentWorker(id, own)) return false;
        // Refresh only the locked, matching-token row; final-result fences still require a live lease.
        long now = clock.millis();
        return jdbc.update(
                "UPDATE awx_jobs SET state='RUNNING',lease_until=?,updated_at=?,error_code=NULL WHERE task_id=? AND job_type='task_ask' AND worker_token=? AND (state='RUNNING' OR (state='OUTCOME_UNKNOWN' AND error_code='worker_lost'))",
                now + LEASE_MS, now, id, own.token()) == 1;
    }
    private boolean isCurrentWorker(String id, Running expected) {
        synchronized (running) {
            return !closed.get() && expected != null && running.get(id) == expected && expected.thread().isAlive();
        }
    }

    public void maintenance() {
        if (enabledTypes.isEmpty() || closed.get()) return;
        long now = clock.millis();
        for (var entry : running.entrySet()) {
            Running own = entry.getValue();
            if (own.acceptedTask()) {
                transactions.executeWithoutResult(tx -> renewLiveTaskOwner(entry.getKey(), own));
                continue;
            }
            int renewed = jdbc.update("UPDATE awx_jobs SET lease_until=?,updated_at=? WHERE task_id=? AND worker_token=? AND state='RUNNING' AND lease_until>?",
                    now + LEASE_MS, now, entry.getKey(), own.token(), now);
            if (renewed != 1) interruptIfCurrent(entry.getKey(), own);
        }
        jdbc.update("UPDATE awx_jobs SET state='OUTCOME_UNKNOWN',error_code=CASE WHEN state='CANCEL_REQUESTED' THEN 'cancelled_worker_lost' ELSE 'worker_lost' END,updated_at=? WHERE state IN ('RUNNING','CANCEL_REQUESTED') AND lease_until<?", now, now);
        transactions.executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM awx_job_results WHERE task_id IN (SELECT task_id FROM awx_jobs WHERE expires_at<=?)", now);
            jdbc.update("DELETE FROM awx_jobs WHERE expires_at<=?", now);
        });
    }

    private void deliverOneCallback() {
        long now = clock.millis();
        for (var handler : handlers.entrySet()) {
            if (!callbackEnabledTypes.contains(handler.getKey())) continue;
            var rows = jdbc.query("SELECT j.task_id,j.payload,r.body,j.callback_attempts FROM awx_jobs j JOIN awx_job_results r ON j.result_ref=r.result_id WHERE j.job_type=? AND j.state='SUCCEEDED' AND j.expires_at>? AND j.callback_next<=? AND (j.callback_state='PENDING' OR (j.callback_state='DELIVERING' AND j.callback_until<?)) ORDER BY j.callback_next LIMIT 1",
                    (rs, i) -> new Callback(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)), handler.getKey(), now, now, now);
            if (rows.isEmpty()) continue;
            Callback row = rows.get(0); String token = UUID.randomUUID().toString();
            int claimed = jdbc.update("UPDATE awx_jobs SET callback_state='DELIVERING',callback_token=?,callback_until=?,callback_attempts=callback_attempts+1 WHERE task_id=? AND (callback_state='PENDING' OR (callback_state='DELIVERING' AND callback_until<?))",
                    token, now + LEASE_MS, row.id(), now);
            if (claimed != 1) continue;
            boolean delivered = false;
            try { delivered = handler.getValue().completed(row.id(), row.payload(), row.body()); }
            catch (Exception ignored) { /* Failure is represented in the durable outbox; never rerun inference. */ }
            int attempts = row.attempts() + 1;
            jdbc.update("UPDATE awx_jobs SET callback_state=?,callback_next=? WHERE task_id=? AND callback_token=? AND callback_state='DELIVERING'",
                    delivered ? "DELIVERED" : attempts >= 5 ? "FAILED" : "PENDING", clock.millis() + (1L << Math.min(attempts, 5)) * 1000L, row.id(), token);
            return;
        }
    }

    @Override public String status(String id) {
        var states = jdbc.query("SELECT state FROM awx_jobs WHERE task_id=? AND job_type<>'understanding_summary_v1' AND (expires_at IS NULL OR expires_at>?)",
                (rs, i) -> rs.getString(1), id, clock.millis());
        return states.isEmpty() ? "NOT_FOUND" : states.get(0);
    }
    @Override public Optional<JobSnapshot> find(String id, String owner) {
        return jdbc.query("SELECT task_id,state,created_at,completed_at,expires_at,result_ref,error_code FROM awx_jobs WHERE task_id=? AND owner_hash=? AND job_type<>'understanding_summary_v1' AND (expires_at IS NULL OR expires_at>?)",
                (rs, i) -> new JobSnapshot(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getObject(5) == null ? null : rs.getLong(5), rs.getString(6), rs.getString(7)),
                id, owner, clock.millis()).stream().findFirst();
    }
    @Override public Optional<String> result(String id, String owner) {
        return jdbc.query("SELECT r.body FROM awx_job_results r JOIN awx_jobs j ON j.result_ref=r.result_id WHERE j.task_id=? AND j.owner_hash=? AND j.job_type<>'understanding_summary_v1' AND j.state='SUCCEEDED' AND j.expires_at>?",
                (rs, i) -> rs.getString(1), id, owner, clock.millis()).stream().findFirst();
    }
    @Override public boolean cancel(String id, String owner) {
        long now = clock.millis();
        int pending = jdbc.update("UPDATE awx_jobs SET state='CANCELLED',completed_at=?,expires_at=?,updated_at=? WHERE task_id=? AND owner_hash=? AND job_type<>'understanding_summary_v1' AND state='PENDING'",
                now, now + RETENTION_MS, now, id, owner);
        int active = jdbc.update("UPDATE awx_jobs SET state='CANCEL_REQUESTED',updated_at=? WHERE task_id=? AND owner_hash=? AND job_type<>'understanding_summary_v1' AND (state='RUNNING' OR (job_type='task_ask' AND state='OUTCOME_UNKNOWN' AND error_code='worker_lost'))", now, id, owner);
        Running local = running.get(id);
        if (active == 1) interruptIfCurrent(id, local);
        if (pending + active > 0) log.info("[JOB_CANCEL_ACCEPTED] taskHash={}", SafeRedactor.hashValue(id));
        return pending + active > 0;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow(); workers.shutdownNow();
        for (var entry : running.entrySet()) {
            entry.getValue().thread().interrupt();
            try { jdbc.update("UPDATE awx_jobs SET state='OUTCOME_UNKNOWN',error_code=CASE WHEN state='CANCEL_REQUESTED' THEN 'cancelled_worker_lost' ELSE 'worker_stopped' END,updated_at=? WHERE task_id=? AND worker_token=? AND state IN ('RUNNING','CANCEL_REQUESTED')",
                    clock.millis(), entry.getKey(), entry.getValue().token()); }
            catch (RuntimeException ignored) { /* Another instance recovers the expiring lease. */ }
        }
    }
    private void guarded(Runnable action) {
        try { action.run(); storeFailure.set(false); }
        catch (RuntimeException failure) {
            if (storeFailure.compareAndSet(false, true)) log.warn("[JOB_STORE_UNAVAILABLE] reason=store_or_schema_unavailable");
        }
    }
    private static String checked(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid_job_field");
        return value;
    }
    private static void requireSize(String value, int max) {
        if (value.getBytes(StandardCharsets.UTF_8).length > max) throw new IllegalArgumentException("job_payload_too_large");
    }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    private record Work(String id, String payload) { }
    private record DerivedWork(String id, String payload, String phase, String body) { }
    private record Running(String token, Thread thread, boolean acceptedTask) {
        private Running(String token, Thread thread) { this(token, thread, false); }
    }
    private record TaskLeaseObservation(String state, String token, String error) { }
    private record Callback(String id, String payload, String body, int attempts) { }
    private record AdmissionRow(String id,String state,String fingerprint,Long expires){}
}
