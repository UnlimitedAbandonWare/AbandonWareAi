package com.example.lms.service.chat;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Owns the terminal, cancellation-fenced persistence sequence for one finalized
 * chat answer. Ordinary stage failures remain fail-soft; cancellation and
 * interruption are terminal and prevent every later stage from running.
 */
public final class FinalizedMemoryPersistence {

    private FinalizedMemoryPersistence() {
    }

    public static void persist(
            ChatRunExecutionContext exactRun,
            CancellationFactory cancellationFactory,
            SuppressionSink suppressionSink,
            Stage... stages) {
        Objects.requireNonNull(cancellationFactory, "cancellationFactory");
        Objects.requireNonNull(suppressionSink, "suppressionSink");
        Objects.requireNonNull(stages, "stages");

        if (exactRun != null && !exactRun.tryBeginCommit()) {
            throw cancellation(cancellationFactory, "cancelled before durable memory commit");
        }

        for (Stage stage : stages) {
            Objects.requireNonNull(stage, "stage");
            try {
                if (exactRun == null) {
                    stage.action().run();
                } else if (!exactRun.runTerminalSideEffect(stage.action())) {
                    throw cancellation(cancellationFactory, "cancelled before terminal side effect");
                }
            } catch (Throwable failure) {
                CancellationException terminal = terminalCancellation(failure);
                if (terminal != null) {
                    throw terminal;
                }
                suppressionSink.suppressed(stage.suppressionReason(), failure);
            }
        }
    }

    private static CancellationException cancellation(CancellationFactory factory, String reason) {
        CancellationException cancellation = factory.create(reason);
        return cancellation == null ? new CancellationException(reason) : cancellation;
    }

    private static CancellationException terminalCancellation(Throwable failure) {
        CancellationException cancellation = null;
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException terminal = new CancellationException("terminal persistence interrupted");
                terminal.initCause(interrupted);
                return terminal;
            }
            if (cancellation == null && cursor instanceof CancellationException candidate) {
                cancellation = candidate;
            }
            Throwable cause = cursor.getCause();
            if (cause == cursor) {
                break;
            }
            cursor = cause;
        }
        return cancellation;
    }

    public record Stage(String suppressionReason, Runnable action) {
        public Stage {
            if (suppressionReason == null || suppressionReason.isBlank()) {
                throw new IllegalArgumentException("suppressionReason is required");
            }
            Objects.requireNonNull(action, "action");
        }
    }

    @FunctionalInterface
    public interface CancellationFactory {
        CancellationException create(String reason);
    }

    @FunctionalInterface
    public interface SuppressionSink {
        void suppressed(String stage, Throwable failure);
    }
}
