package ai.abandonware.nova.boot.exec.zombie;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ZombieBreederContainmentAspect {
    private final ZombieBreederDetector detector;
    private final CleanPoolFactory cleanPoolFactory;
    private final ZombieContainmentMigrator migrator;
    private final ZombieBreederProperties props;

    public ZombieBreederContainmentAspect(
            ZombieBreederDetector detector,
            CleanPoolFactory cleanPoolFactory,
            ZombieContainmentMigrator migrator,
            ZombieBreederProperties props) {
        this.detector = detector;
        this.cleanPoolFactory = cleanPoolFactory;
        this.migrator = migrator;
        this.props = props == null ? new ZombieBreederProperties() : props;
    }

    @Around("execution(* ai.abandonware.nova.boot.exec.CancelShieldExecutorService.invokeAll(java.util.Collection))")
    public Object containInvokeAll(ProceedingJoinPoint pjp) throws Throwable {
        return containInvokeAllInternal(pjp, false);
    }

    @Around("execution(* ai.abandonware.nova.boot.exec.CancelShieldExecutorService.invokeAll(java.util.Collection, long, java.util.concurrent.TimeUnit))")
    public Object containTimedInvokeAll(ProceedingJoinPoint pjp) throws Throwable {
        return containInvokeAllInternal(pjp, true);
    }

    @Around("execution(* com.example.lms.service.rag.burst.ExtremeZSystemHandler.execute(..))")
    public Object observeExtremeZExecute(ProceedingJoinPoint pjp) throws Throwable {
        return proceedWithVerdict(pjp, "extremez.execute", 0);
    }

    private Object containInvokeAllInternal(ProceedingJoinPoint pjp, boolean timed) throws Throwable {
        Object[] args = pjp.getArgs();
        Collection<? extends Callable<?>> tasks = callableCollection(args);
        int currentInflight = tasks == null ? 0 : tasks.size();
        ContaminationVerdict verdict;
        try {
            verdict = verdict("cancelShield.invokeAll", currentInflight);
        } catch (Throwable error) {
            traceAspectError(error);
            TraceStore.put("zombie.aspect.fallback.reason", "detector_fail_open");
            return proceedOriginal(pjp);
        }
        if (verdict != ContaminationVerdict.CONTAMINATED || tasks == null || tasks.isEmpty()) {
            return proceedOriginal(pjp);
        }

        CancelShieldExecutorService cleanPool = null;
        String poolTag = null;
        try {
            cleanPool = cleanPoolFactory.createCleanPool("cancelShield.invokeAll", currentInflight);
            poolTag = String.valueOf(TraceStore.get("zombie.cleanPool.poolTag"));
            Object result = invokeAllOnCleanPool(cleanPool, tasks, timed, args);
            migrator.record(new ZombieContainmentMigrator.MigrationResult(currentInflight, 0, 0, true));
            TraceStore.put("zombie.aspect.cleanPoolReplay.used", true);
            TraceStore.put("zombie.aspect.cleanPoolReplay.inflight", currentInflight);
            TraceStore.put("zombie.aspect.originalProceed.count", TraceStore.getLong("zombie.aspect.originalProceed.count"));
            TraceStore.put("zombie.aspect.fallback.reason", "clean_pool_replay");
            return result;
        } catch (Throwable error) {
            traceAspectError(error);
            TraceStore.put("zombie.aspect.fallback.reason", "original_pool_fallthrough");
            return proceedOriginal(pjp);
        } finally {
            if (cleanPool != null) {
                cleanPoolFactory.retire(cleanPool, poolTag);
            }
        }
    }

    private Object proceedWithVerdict(ProceedingJoinPoint pjp, String ownerHint, int currentInflight) throws Throwable {
        try {
            verdict(ownerHint, currentInflight);
        } catch (Throwable error) {
            traceAspectError(error);
        }
        return pjp.proceed();
    }

    private static Object proceedOriginal(ProceedingJoinPoint pjp) throws Throwable {
        TraceStore.inc("zombie.aspect.originalProceed.count");
        return pjp.proceed();
    }

    private ContaminationVerdict verdict(String ownerHint, int currentInflight) {
        ContaminationVerdict verdict = detector == null
                ? ContaminationVerdict.CLEAN
                : detector.assess(ownerHint, currentInflight);
        if (detector != null) {
            detector.recordVerdict(verdict, ownerHint);
        }
        return verdict;
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends Callable<?>> callableCollection(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Collection<?> raw)) {
            return List.of();
        }
        return (Collection<? extends Callable<?>>) raw;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static List<? extends Future<?>> invokeAllOnCleanPool(
            CancelShieldExecutorService cleanPool,
            Collection<? extends Callable<?>> tasks,
            boolean timed,
            Object[] args) throws InterruptedException {
        Collection typedTasks = tasks;
        if (timed) {
            return cleanPool.invokeAll(typedTasks, ((Number) args[1]).longValue(), (TimeUnit) args[2]);
        }
        return cleanPool.invokeAll(typedTasks);
    }

    private static void traceAspectError(Throwable error) {
        TraceStore.put("zombie.aspect.error",
                SafeRedactor.traceLabelOrFallback(error == null ? null : error.getClass().getSimpleName(), "unknown"));
        TraceStore.put("zombie.aspect.errorHash", SafeRedactor.hashValue(messageOf(error)));
        TraceStore.put("zombie.aspect.errorLength", messageLength(error));
    }

    @SuppressWarnings("unused")
    public ZombieBreederProperties properties() {
        return props;
    }

    private static String messageOf(Throwable error) {
        return error == null ? null : error.getMessage();
    }

    private static int messageLength(Throwable error) {
        String message = messageOf(error);
        return message == null ? 0 : message.length();
    }
}
