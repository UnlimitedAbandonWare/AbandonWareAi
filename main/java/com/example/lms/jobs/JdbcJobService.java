package com.example.lms.jobs;

import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean(), storeFailure = new AtomicBoolean();
    private final Semaphore slots = new Semaphore(4);
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> daemon(r, "durable-job-worker"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> daemon(r, "durable-job-clock"));

    public JdbcJobService(DataSource dataSource, ObjectMapper mapper, Clock clock) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
        this.jdbc.setQueryTimeout(5);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public void registerHandler(String type, JobHandler handler) {
        if (handlers.putIfAbsent(checked(type, 64), Objects.requireNonNull(handler)) != null)
            throw new IllegalStateException("duplicate_job_handler");
    }
    @Override public boolean runsPersistedJobs() { return true; }
    @Override public String enqueue(String payload) {
        return enqueue("legacy", Objects.requireNonNull(payload), Map.of(), null);
    }
    @Override public String enqueue(String type, Object payload, Map<String, Object> metadata, String correlationId) {
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
        String identity=admissionIdentity(type,owner,key,fingerprint);
        return jdbc.query("SELECT task_id,state,request_fingerprint,expires_at FROM awx_jobs WHERE admission_key=? AND owner_hash=? AND job_type=?",
                (rs,row)->new AdmissionRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4)==null?null:rs.getLong(4)),identity,owner,type).stream()
                .filter(row->row.expires()==null||row.expires()>clock.millis()).findFirst().map(row->{if(!fingerprint.equals(row.fingerprint()))throw new IdempotencyConflict();return new Admission(row.id(),true,row.state());});
    }
    @Override public Admission enqueueOnce(String type,Object payload,Map<String,Object> metadata,String correlationId,String key,String fingerprint){
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

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        scheduler.scheduleWithFixedDelay(() -> guarded(this::maintenance), 0, 500, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            if (closed.get() || !slots.tryAcquire()) return;
            try { workers.execute(() -> { try { guarded(this::runPendingOnce); } finally { slots.release(); } }); }
            catch (RejectedExecutionException rejected) { slots.release(); }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    /** Bounded one-job poll; separately callable for deterministic recovery tests. */
    public void runPendingOnce() {
        if (closed.get()) return;
        deliverOneCallback();
        for (var entry : handlers.entrySet()) {
            var rows = jdbc.query("SELECT task_id,payload FROM awx_jobs WHERE state='PENDING' AND job_type=? ORDER BY created_at,task_id LIMIT 1",
                    (rs, i) -> new Work(rs.getString(1), rs.getString(2)), entry.getKey());
            if (rows.isEmpty()) continue;
            Work work = rows.get(0); String token = UUID.randomUUID().toString(); long now = clock.millis();
            if (jdbc.update("UPDATE awx_jobs SET state='RUNNING',worker_token=?,lease_until=?,updated_at=? WHERE task_id=? AND state='PENDING'",
                    token, now + LEASE_MS, now, work.id()) != 1) continue;
            execute(work, token, entry.getValue());
            return;
        }
    }

    private void execute(Work work, String token, JobHandler handler) {
        running.put(work.id(), new Running(token, Thread.currentThread()));
        try {
            if (closed.get()) return;
            String body = Objects.requireNonNull(handler.execute(work.payload()));
            requireSize(body, MAX_RESULT_BYTES);
            boolean callback = handler.needsCompletion(work.payload());
            String resultId = UUID.randomUUID().toString(); long now = clock.millis();
            transactions.executeWithoutResult(tx -> {
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
                jdbc.update("UPDATE awx_jobs SET state='CANCELLED',completed_at=?,expires_at=?,updated_at=? WHERE task_id=? AND state='CANCEL_REQUESTED' AND worker_token=?",
                        now, now + RETENTION_MS, now, work.id(), token);
            } finally { running.remove(work.id()); Thread.interrupted(); }
        }
    }

    public void maintenance() {
        long now = clock.millis();
        for (var entry : running.entrySet()) {
            Running own = entry.getValue();
            int renewed = jdbc.update("UPDATE awx_jobs SET lease_until=?,updated_at=? WHERE task_id=? AND worker_token=? AND state='RUNNING' AND lease_until>?",
                    now + LEASE_MS, now, entry.getKey(), own.token(), now);
            if (renewed != 1) own.thread().interrupt();
        }
        jdbc.update("UPDATE awx_jobs SET state='OUTCOME_UNKNOWN',error_code='worker_lost',updated_at=? WHERE state IN ('RUNNING','CANCEL_REQUESTED') AND lease_until<?", now, now);
        transactions.executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM awx_job_results WHERE task_id IN (SELECT task_id FROM awx_jobs WHERE expires_at<=?)", now);
            jdbc.update("DELETE FROM awx_jobs WHERE expires_at<=?", now);
        });
    }

    private void deliverOneCallback() {
        long now = clock.millis();
        for (var handler : handlers.entrySet()) {
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
        var states = jdbc.query("SELECT state FROM awx_jobs WHERE task_id=? AND (expires_at IS NULL OR expires_at>?)",
                (rs, i) -> rs.getString(1), id, clock.millis());
        return states.isEmpty() ? "NOT_FOUND" : states.get(0);
    }
    @Override public Optional<JobSnapshot> find(String id, String owner) {
        return jdbc.query("SELECT task_id,state,created_at,completed_at,expires_at,result_ref,error_code FROM awx_jobs WHERE task_id=? AND owner_hash=? AND (expires_at IS NULL OR expires_at>?)",
                (rs, i) -> new JobSnapshot(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getObject(5) == null ? null : rs.getLong(5), rs.getString(6), rs.getString(7)),
                id, owner, clock.millis()).stream().findFirst();
    }
    @Override public Optional<String> result(String id, String owner) {
        return jdbc.query("SELECT r.body FROM awx_job_results r JOIN awx_jobs j ON j.result_ref=r.result_id WHERE j.task_id=? AND j.owner_hash=? AND j.state='SUCCEEDED' AND j.expires_at>?",
                (rs, i) -> rs.getString(1), id, owner, clock.millis()).stream().findFirst();
    }
    @Override public boolean cancel(String id, String owner) {
        long now = clock.millis();
        int pending = jdbc.update("UPDATE awx_jobs SET state='CANCELLED',completed_at=?,expires_at=?,updated_at=? WHERE task_id=? AND owner_hash=? AND state='PENDING'",
                now, now + RETENTION_MS, now, id, owner);
        int active = jdbc.update("UPDATE awx_jobs SET state='CANCEL_REQUESTED',updated_at=? WHERE task_id=? AND owner_hash=? AND state='RUNNING'", now, id, owner);
        Running local = running.get(id);
        if (active == 1 && local != null) local.thread().interrupt();
        if (pending + active > 0) log.info("[JOB_CANCEL_ACCEPTED] taskHash={}", SafeRedactor.hashValue(id));
        return pending + active > 0;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow(); workers.shutdownNow();
        for (var entry : running.entrySet()) {
            entry.getValue().thread().interrupt();
            try { jdbc.update("UPDATE awx_jobs SET state='OUTCOME_UNKNOWN',error_code='worker_stopped',updated_at=? WHERE task_id=? AND worker_token=? AND state IN ('RUNNING','CANCEL_REQUESTED')",
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
    private record Running(String token, Thread thread) { }
    private record Callback(String id, String payload, String body, int attempts) { }
    private record AdmissionRow(String id,String state,String fingerprint,Long expires){}
}
