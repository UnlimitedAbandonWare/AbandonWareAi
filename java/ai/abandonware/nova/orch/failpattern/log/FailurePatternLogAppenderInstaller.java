package ai.abandonware.nova.orch.failpattern.log;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;

/**
 * Installs FailurePatternLogAppender into Logback at runtime.
 *
 * <p>We do this programmatically so we can use Spring-injected dependencies
 * (MeterRegistry/ObjectMapper/properties) without editing logback XML.
 */
public final class FailurePatternLogAppenderInstaller {

    private static final String APPENDER_NAME = "NOVA_FAILURE_PATTERN";

    private final FailurePatternOrchestrator orchestrator;

    public FailurePatternLogAppenderInstaller(FailurePatternOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostConstruct
    public void install() {
        try {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

            if (root.getAppender(APPENDER_NAME) != null) {
                return;
            }

            FailurePatternLogAppender appender = new FailurePatternLogAppender(orchestrator);
            appender.setName(APPENDER_NAME);
            appender.setContext(ctx);
            appender.start();

            root.addAppender(appender);
        } catch (Exception ignored) {
            // fail-soft
        }
    }
}
