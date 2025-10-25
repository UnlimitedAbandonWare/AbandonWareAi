package com.abandonware.ai.addons.onnx;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.abandonware.ai.addons.config.AddonsProperties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;




/** CE 병목 보호: 동시 처리 상한 + 대기 시간 초과 시 CE 생략 */
public class OnnxSemaphoreGate {
    private static final Logger log = Logger.getLogger(OnnxSemaphoreGate.class.getName());
    private final Semaphore sem;
    private final AddonsProperties props;

    public OnnxSemaphoreGate(AddonsProperties props) {
        this.props = props;
        this.sem = new Semaphore(Math.max(1, props.getOnnx().getMaxConcurrent()));
    }

    public <T> T withPermit(Supplier<T> ceTask, Supplier<T> fallback) {
        long waitMs = props.getOnnx().getQueueWaitMs();
        var tb = TimeBudgetContext.get();
        if (tb != null) waitMs = Math.min(waitMs, Math.max(1, tb.remainingMillis() / 2));
        boolean acquired = false;
        try {
            acquired = sem.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.fine("onnx gate: skip CE (queue wait exceeded)");
                return fallback.get();
            }
            if (tb != null && tb.expired()) {
                log.fine("onnx gate: skip CE (budget expired)");
                return fallback.get();
            }
            return ceTask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback.get();
        } finally {
            if (acquired) sem.release();
        }
    }
}