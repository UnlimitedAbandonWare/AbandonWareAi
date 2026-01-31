package ai.abandonware.nova.orch.failpattern.log;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback appender that observes log messages and forwards them
 * to the FailurePatternOrchestrator.
 *
 * <p>
 * Performance: we only inspect WARN+ events.
 */
public final class FailurePatternLogAppender extends AppenderBase<ILoggingEvent> {

    private final FailurePatternOrchestrator orchestrator;

    public FailurePatternLogAppender(FailurePatternOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        try {
            if (event.getLevel() != null && event.getLevel().toInt() < Level.WARN.toInt()) {
                return;
            }
            orchestrator.onLogEvent(
                    event.getTimeStamp(),
                    event.getLoggerName(),
                    event.getLevel() == null ? null : event.getLevel().toString(),
                    event.getFormattedMessage());
        } catch (Exception ignored) {
            // fail-soft
        }
    }
}
