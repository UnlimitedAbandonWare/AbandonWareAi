package com.example.lms.jobs;

import com.example.lms.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JdbcJobF01DerivedTest {
    static final String TYPE = JobService.UNDERSTANDING_TYPE, OWNER = "a".repeat(64);
    DriverManagerDataSource ds;
    LocalContainerEntityManagerFactoryBean factory;
    JpaTransactionManager tm;
    TransactionTemplate tx;
    JdbcTemplate sql;
    JdbcJobService jobs;
    AtomicInteger computes;
    final JobService.DerivedIdentity identity = new JobService.DerivedIdentity(OWNER, 1, "run-original", "e".repeat(64));
    @BeforeEach void setup() {
        ds = new DriverManagerDataSource("jdbc:h2:mem:derived_" + UUID.randomUUID()
                + ";MODE=MariaDB;DB_CLOSE_DELAY=-1", "sa", "");
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(ChatSession.class.getName(), ChatMessage.class.getName(), Administrator.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        tm = new JpaTransactionManager(Objects.requireNonNull(factory.getObject()));
        tx = new TransactionTemplate(tm);
        sql = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(
                new FileSystemResource("main/resources/db/migration/V20260912__durable_jobs.sql"),
                new FileSystemResource("main/resources/db/migration/V20260912_03__job_idempotency.sql"),
                new FileSystemResource("main/resources/db/migration/V20260929__f01_understanding_receipt.sql")).execute(ds);
        sql.execute("CREATE TABLE synthetic_effect(effect_key VARCHAR(64) PRIMARY KEY)");
        computes = new AtomicInteger();
        jobs = service();
    }
    JdbcJobService service() { return new JdbcJobService(ds, new ObjectMapper(), Clock.systemUTC(), tm, Set.of(TYPE), Set.of()); }
    @AfterEach void close() { jobs.close(); factory.destroy(); }
    String enqueue() {
        return tx.execute(t -> jobs.enqueueDerivedOnce(Map.of("synthetic", true), identity, "admission", "f".repeat(64)).taskId());
    }
    String state(String id) { return sql.queryForObject("SELECT state FROM awx_jobs WHERE task_id=?", String.class, id); }
    int effects() { return sql.queryForObject("SELECT COUNT(*) FROM synthetic_effect", Integer.class); }
    JobService.DerivedJobHandler handler() {
        return new JobService.DerivedJobHandler() {
            public String compute(String payload) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "provider I/O outside SQL");
                computes.incrementAndGet(); return "{\"summary\":\"synthetic\"}";
            }
            public void commit(JobService.DerivedClaim claim, String payload, String prepared) {
                tx.executeWithoutResult(t -> {
                    jobs.requireDerivedLease(claim);
                    sql.update("INSERT INTO synthetic_effect VALUES(?)", identity.effectKey());
                    jobs.completeDerived(claim);
                });
            }
        };
    }
    @Test void restartRecoversCommittedIntentAndCallsProviderOutsideTransaction() {
        jobs.registerDerivedHandler(TYPE, handler());
        assertTrue(jobs.derivedReady(TYPE));
        String id = enqueue();
        jobs.close(); jobs = service(); jobs.registerDerivedHandler(TYPE, handler());
        jobs.runPendingOnce(); jobs.runPendingOnce();
        assertEquals("SUCCEEDED", state(id)); assertEquals(1, effects()); assertEquals(1, computes.get());
    }
    @Test void preparedCheckpointRetriesOnlyTheCommitAfterRestart() {
        var normal = handler();
        jobs.registerDerivedHandler(TYPE, new JobService.DerivedJobHandler() {
            public String compute(String p) throws Exception { return normal.compute(p); }
            public void commit(JobService.DerivedClaim c, String p, String r) {
                throw new IllegalStateException("injected_before_commit");
            }
        });
        String id = enqueue(); jobs.runPendingOnce();
        assertEquals("OUTCOME_UNKNOWN", state(id));
        assertEquals("COMMIT", sql.queryForObject("SELECT phase FROM awx_jobs WHERE task_id=?", String.class, id));
        assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM awx_job_results", Integer.class));
        jobs.close(); jobs = service(); jobs.registerDerivedHandler(TYPE, normal);
        sql.update("UPDATE awx_jobs SET next_attempt_at=0 WHERE task_id=?", id);
        jobs.runPendingOnce();
        assertEquals("SUCCEEDED", state(id)); assertEquals(1, computes.get()); assertEquals(1, effects());
        assertEquals(2, sql.queryForObject("SELECT commit_attempts FROM awx_jobs WHERE task_id=?", Integer.class, id));
    }
    @Test void staleWorkerCannotWriteOrAcknowledge() {
        var normal = handler();
        jobs.registerDerivedHandler(TYPE, new JobService.DerivedJobHandler() {
            public String compute(String p) throws Exception { return normal.compute(p); }
            public void commit(JobService.DerivedClaim c, String p, String r) throws Exception {
                sql.update("UPDATE awx_jobs SET worker_token='replacement',lease_until=0 WHERE task_id=?", c.taskId());
                normal.commit(c, p, r);
            }
        });
        String id = enqueue(); jobs.runPendingOnce(); jobs.maintenance();
        assertEquals("OUTCOME_UNKNOWN", state(id)); assertEquals(0, effects());
    }
    @Test void cancellationStaysRequestedUntilComputePhysicallyReturns() throws Exception {
        physicalCancellation(false);
    }
    @Test void cancellationAfterLeaseLossStillWaitsForPhysicalCompletion() throws Exception {
        physicalCancellation(true);
    }
    private void physicalCancellation(boolean lostLease) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var normal = handler();
        jobs.registerDerivedHandler(TYPE, new JobService.DerivedJobHandler() {
            public String compute(String p) {
                entered.countDown();
                boolean waiting = true;
                while(waiting) try { release.await(); waiting = false; } catch(InterruptedException ignored) { }
                return "{}";
            }
            public void commit(JobService.DerivedClaim c, String p, String r) throws Exception { normal.commit(c,p,r); }
        });
        String id = enqueue();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var run = executor.submit(jobs::runPendingOnce);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (lostLease) {
                sql.update("UPDATE awx_jobs SET lease_until=0 WHERE task_id=?", id);
                jobs.maintenance();
                assertEquals("OUTCOME_UNKNOWN", state(id));
            }
            assertFalse(jobs.cancelDerivedRun("b".repeat(64), 1, "run-original"));
            assertFalse(jobs.cancelDerivedRun(OWNER, 1, "other-run"));
            assertTrue(jobs.cancelDerivedRun(OWNER, 1, "run-original"));
            assertEquals(lostLease ? "OUTCOME_UNKNOWN" : "CANCEL_REQUESTED", state(id));
            assertFalse(run.isDone()); assertEquals(0, effects());
            release.countDown(); run.get(5, TimeUnit.SECONDS);
            assertEquals("CANCELLED", state(id)); assertEquals(0, effects());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void internalJobsNeverExposePublicStatusResultOrCancel() {
        jobs.registerDerivedHandler(TYPE, handler()); String id = enqueue();
        assertEquals("NOT_FOUND", jobs.status(id));
        assertTrue(jobs.find(id, OWNER).isEmpty()); assertTrue(jobs.find(id, "foreign").isEmpty());
        assertFalse(jobs.cancel(id, OWNER)); assertEquals("PENDING", state(id));
        jobs.runPendingOnce(); assertTrue(jobs.result(id, OWNER).isEmpty());
    }
    @Test void staleMaintenanceCannotInterruptNextJobOnReusedWorkerThread() throws Exception {
        var firstEntered = new CountDownLatch(1); var firstReturn = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1); var secondReturn = new CountDownLatch(1);
        var renewalCaptured = new CountDownLatch(1); var releaseRenewal = new CountDownLatch(1);
        var secondInterrupted = new AtomicInteger();
        jobs.registerDerivedHandler(TYPE, new JobService.DerivedJobHandler() {
            public String compute(String p) throws Exception {
                if (computes.incrementAndGet() == 1) { firstEntered.countDown(); firstReturn.await(); }
                else {
                    secondEntered.countDown();
                    boolean waiting = true;
                    while (waiting) try { secondReturn.await(); waiting = false; }
                    catch (InterruptedException ignored) { secondInterrupted.incrementAndGet(); }
                }
                return "{}";
            }
            public void commit(JobService.DerivedClaim c, String p, String r) { jobs.completeDerived(c); }
        });
        String first = enqueue();
        var probe = org.mockito.Mockito.spy(sql);
        org.mockito.Mockito.doAnswer(call -> {
            Object[] values = call.getArguments();
            if (java.util.Arrays.asList(values).contains(first)) {
                renewalCaptured.countDown();
                assertTrue(releaseRenewal.await(5, TimeUnit.SECONDS));
                return 0;
            }
            return call.callRealMethod();
        }).when(probe).update(org.mockito.ArgumentMatchers.startsWith("UPDATE awx_jobs SET lease_until="),
                org.mockito.ArgumentMatchers.any(Object[].class));
        org.springframework.test.util.ReflectionTestUtils.setField(jobs, "jdbc", probe);
        var worker = Executors.newSingleThreadExecutor();
        var maintenance = Executors.newSingleThreadExecutor();
        try {
            var a = worker.submit(jobs::runPendingOnce);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var sweep = maintenance.submit(jobs::maintenance);
            assertTrue(renewalCaptured.await(5, TimeUnit.SECONDS));
            firstReturn.countDown(); a.get(5, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", state(first));
            String second = tx.execute(t -> jobs.enqueueDerivedOnce(Map.of("second", true),
                    new JobService.DerivedIdentity(OWNER, 1, "run-second", "d".repeat(64)),
                    "second", "b".repeat(64)).taskId());
            var b = worker.submit(jobs::runPendingOnce);
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            releaseRenewal.countDown(); sweep.get(5, TimeUnit.SECONDS);
            secondReturn.countDown(); b.get(5, TimeUnit.SECONDS);
            assertEquals(0, secondInterrupted.get(), "stale job must not interrupt the next provider slot");
            assertEquals("SUCCEEDED", state(second));
        } finally {
            firstReturn.countDown(); secondReturn.countDown(); releaseRenewal.countDown();
            worker.shutdownNow(); maintenance.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS); maintenance.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    @Test void legacyRegistrationCannotBypassDerivedCommit() {
        assertThrows(IllegalArgumentException.class, () -> jobs.registerHandler(TYPE, p -> "{}"));
        assertFalse(jobs.derivedReady(TYPE));
    }
    @Test void computeRetriesAreBoundedAndNeverRegenerateOrigin() {
        jobs.registerDerivedHandler(TYPE, new JobService.DerivedJobHandler() {
            public String compute(String p) { computes.incrementAndGet(); throw new IllegalStateException("synthetic_transport_failure"); }
            public void commit(JobService.DerivedClaim c, String p, String r) { fail("no result"); }
        });
        String id = enqueue();
        for(int i=0;i<5;i++) { sql.update("UPDATE awx_jobs SET next_attempt_at=0 WHERE task_id=?", id); jobs.runPendingOnce(); }
        assertEquals(2, computes.get()); assertEquals("OUTCOME_UNKNOWN", state(id)); assertEquals(0,effects());
    }
    @Test void cancelledUnknownJobIsNeverRecomputed() {
        jobs.registerDerivedHandler(TYPE, handler()); String id = enqueue();
        sql.update("UPDATE awx_jobs SET state='OUTCOME_UNKNOWN',error_code='worker_lost' WHERE task_id=?",id);
        assertTrue(jobs.cancelDerivedRun(OWNER, 1, "run-original"));
        jobs.runPendingOnce(); assertEquals(0, computes.get()); assertEquals("OUTCOME_UNKNOWN", state(id));
        assertEquals("cancelled_worker_lost", sql.queryForObject("SELECT error_code FROM awx_jobs WHERE task_id=?", String.class, id));
    }
    @Test void admissionAndSuccessRequireAnExistingTransaction() {
        jobs.registerDerivedHandler(TYPE, handler());
        assertThrows(IllegalStateException.class, () -> jobs.enqueueDerivedOnce(Map.of(), identity, "key", "f".repeat(64)));
        assertThrows(IllegalStateException.class, () -> jobs.completeDerived(new JobService.DerivedClaim("id", "token")));
    }

    @Test void engineRollsBackUnfencedCommitWithoutSuccessAcknowledgement() {
        jobs.registerDerivedHandler(TYPE, new JobService.DerivedJobHandler() {
            public String compute(String p) { return "{}"; }
            public void commit(JobService.DerivedClaim c, String p, String r) {
                sql.update("INSERT INTO synthetic_effect VALUES(?)", identity.effectKey());
                // A broken adapter omits both guards. The engine must still roll back.
            }
        });
        String id = enqueue(); jobs.runPendingOnce();
        assertEquals("OUTCOME_UNKNOWN", state(id)); assertEquals(0, effects());
    }
    @Test void engineRollsBackRecoveryWritesWhenNoReceiptWasConfirmed() {
        jobs.registerDerivedHandler(TYPE, new JobService.DerivedJobHandler() {
            public boolean recover(JobService.DerivedClaim c, String p) {
                sql.update("INSERT INTO synthetic_effect VALUES(?)", identity.effectKey());
                return false;
            }
            public String compute(String p) { return "{}"; }
            public void commit(JobService.DerivedClaim c, String p, String r) {
                tx.executeWithoutResult(t -> jobs.completeDerived(c));
            }
        });
        String id = enqueue(); jobs.runPendingOnce();
        assertEquals("SUCCEEDED", state(id)); assertEquals(0, effects());
    }
    @Test void engineRollsBackUnfencedCommitWhenConcurrentCancelWins() {
        jobs.registerDerivedHandler(TYPE, new JobService.DerivedJobHandler() {
            public String compute(String p) { return "{}"; }
            public void commit(JobService.DerivedClaim c, String p, String r) {
                sql.update("INSERT INTO synthetic_effect VALUES(?)", identity.effectKey());
                var concurrent = new TransactionTemplate(tm);
                concurrent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                concurrent.executeWithoutResult(t -> jobs.cancelDerivedRun(OWNER, 1, "run-original"));
            }
        });
        String id = enqueue(); jobs.runPendingOnce();
        assertEquals("CANCELLED", state(id)); assertEquals(0, effects());
    }
    @Test void missingReceiptSchemaCannotAdmitDerivedWork() {
        jobs.registerDerivedHandler(TYPE, handler());
        sql.execute("DROP TABLE awx_understanding_receipts");
        assertFalse(jobs.derivedReady(TYPE));
        assertThrows(RuntimeException.class, this::enqueue);
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM awx_jobs",Integer.class));
    }
}
