package ai.abandonware.subagent.mcp;

import com.abandonware.ai.agent.orchestrator.subagent.GlmAgentCore;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProvider;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentProviderConfiguration;
import com.abandonware.ai.agent.orchestrator.subagent.SubagentTask;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executable Java 17 STDIO MCP server for the shared read-only GLM agent core. */
public final class GlmAgentMcpServer {

    private static final Logger log = LoggerFactory.getLogger(GlmAgentMcpServer.class);
    private static final String SERVER_NAME = "glm-agent";
    private static final String SERVER_VERSION = "1.0.0";
    private static final int MAX_INITIALIZE_FRAME_BYTES = 8_192;

    private GlmAgentMcpServer() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.err.flush();
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        if (!stdioRequested(args)) {
            System.err.println("timestamp=" + Instant.now()
                    + " event=mcp.server.failed transport=unsupported correlationId=none taskIdHash=none "
                    + "role=server provider=none status=failed elapsedMs=0 fallbackUsed=false "
                    + "errorClass=UNSUPPORTED_TRANSPORT circuitState=none tool=none");
            return 2;
        }

        RuntimeResources resources = null;
        try {
            ConfigurableApplicationContext context = startContext(args);
            GlmAgentCore core = context.getBean(GlmAgentCore.class);
            GlmAgentMcpTools tools = new GlmAgentMcpTools(core);
            ProtocolEvidence protocolEvidence = new ProtocolEvidence(tools::close);
            ProtocolObservingInputStream observedInput = new ProtocolObservingInputStream(
                    System.in, protocolEvidence);
            ProtocolObservingOutputStream observedOutput = new ProtocolObservingOutputStream(
                    System.out, protocolEvidence);
            StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    McpJsonDefaults.getMapper(), observedInput, observedOutput);
            McpAsyncServer server = McpServer.async(transport)
                    .serverInfo(new McpSchema.Implementation(SERVER_NAME, SERVER_VERSION))
                    .instructions("Read-only bounded reasoning over supplied input; no workspace access or mutation.")
                    .requestTimeout(Duration.ofSeconds(125))
                    .strictToolNameValidation(true)
                    .validateToolInputs(true)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .tools(tools.specifications())
                    .build();

            resources = new RuntimeResources(context, tools, server);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(resources::close, "glm-agent-mcp-shutdown"));
            String sdkVersion = Objects.requireNonNullElse(
                    McpServer.class.getPackage().getImplementationVersion(), "unknown");
            log.info("event=mcp.server.started transport=stdio correlationId=none taskIdHash=none role=server "
                            + "provider=none status=started elapsedMs=0 fallbackUsed=false errorClass=NONE "
                            + "circuitState=none tool=none serverName={} serverVersion={} sdkVersion={} toolCount=4",
                    SERVER_NAME, SERVER_VERSION, safeLabel(sdkVersion, "unknown"));
            // The official STDIO transport owns non-daemon inbound/outbound workers.
            // Returning leaves it active; stdin EOF closes the session and starts JVM shutdown.
            return 0;
        } catch (Throwable startupFailure) {
            if (resources != null) {
                resources.close();
            }
            log.error("event=mcp.server.failed transport=stdio correlationId=none taskIdHash=none role=server "
                            + "provider=none status=failed elapsedMs=0 fallbackUsed=false "
                            + "errorClass=STARTUP_FAILED circuitState=none tool=none failureType={}",
                    safeLabel(startupFailure.getClass().getSimpleName(), "unknown"));
            return 1;
        }
    }

    private static ConfigurableApplicationContext startContext(String[] args) {
        SpringApplication application = new SpringApplication(RuntimeConfiguration.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(true);
        return application.run(args == null ? new String[0] : args);
    }

    private static boolean stdioRequested(String[] args) {
        if (args == null) {
            return true;
        }
        return Arrays.stream(args)
                .filter(Objects::nonNull)
                .filter(argument -> argument.startsWith("--transport="))
                .allMatch("--transport=stdio"::equals);
    }

    private static String safeLabel(String value, String fallback) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            return fallback;
        }
        return value;
    }

    /** Minimal non-web context: provider adapters plus their existing config owners only. */
    @Configuration(proxyBeanMethods = false)
    @Import(SubagentProviderConfiguration.class)
    public static class RuntimeConfiguration {

        @Bean
        LlmGatewayFailureClassifier glmAgentFailureClassifier() {
            return new LlmGatewayFailureClassifier();
        }

        @Bean
        ModelRuntimeHealthTracker glmAgentModelRuntimeHealthTracker() {
            return new ModelRuntimeHealthTracker();
        }

        @Bean
        KeyResolver glmAgentKeyResolver(Environment environment) {
            return new KeyResolver(environment);
        }

        @Bean
        DynamicChatModelFactory glmAgentDynamicChatModelFactory(
                Environment environment,
                KeyResolver keyResolver,
                ModelRuntimeHealthTracker healthTracker) {
            return new DynamicChatModelFactory(environment, keyResolver, healthTracker);
        }

        @Bean("devinCliSubagentProvider")
        SubagentProvider devinCliSubagentProvider(Environment environment) {
            return new DevinCliProvider(environment);
        }

        @Bean("glmAgentMcpDeterministicProvider")
        @ConditionalOnProperty(prefix = "glm.agent.mcp", name = "deterministic-provider", havingValue = "true")
        SubagentProvider glmAgentMcpDeterministicProvider(Environment environment) {
            long delayMs = Math.max(0L, environment.getProperty(
                    "glm.agent.mcp.deterministic-delay-ms", Long.class, 0L));
            return new SubagentProvider() {
                @Override
                public String id() {
                    return "deterministic";
                }

                @Override
                public int order() {
                    return 15;
                }

                @Override
                public Availability availability() {
                    return Availability.enabled();
                }

                @Override
                public String execute(SubagentTask task, long timeoutMs) throws Exception {
                    if (delayMs > 0L) {
                        Thread.sleep(delayMs);
                    }
                    String role = task == null ? "unknown" : safeLabel(task.role(), "unknown");
                    return "Deterministic read-only result for role " + role + ".";
                }
            };
        }
    }

    private static final class RuntimeResources implements AutoCloseable {
        private final ConfigurableApplicationContext context;
        private final GlmAgentMcpTools tools;
        private final McpAsyncServer server;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RuntimeResources(ConfigurableApplicationContext context,
                                 GlmAgentMcpTools tools,
                                 McpAsyncServer server) {
            this.context = context;
            this.tools = tools;
            this.server = server;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            tools.close();
            try {
                server.closeGracefully().block(Duration.ofSeconds(1));
            } catch (RuntimeException ignored) {
                // Session may already be closed by stdin EOF.
            }
            try {
                context.close();
            } catch (RuntimeException ignored) {
                // JVM shutdown remains best-effort and bounded.
            }
            log.info("event=mcp.server.stopped transport=stdio correlationId=none taskIdHash=none role=server "
                    + "provider=none status=stopped elapsedMs=0 fallbackUsed=false errorClass=NONE "
                    + "circuitState=none tool=none");
        }
    }

    /**
     * Observes only the bounded initialize frame so diagnostics can record the
     * negotiated version. Raw frames, arguments, prompts, and outputs are never logged.
     */
    private static final class ProtocolObservingInputStream extends FilterInputStream {
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(512);
        private final ProtocolEvidence evidence;
        private boolean discardUntilNewline;

        private ProtocolObservingInputStream(InputStream input, ProtocolEvidence evidence) {
            super(input);
            this.evidence = evidence;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                observe(value);
            } else {
                evidence.onEof();
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            for (int index = 0; index < count; index++) {
                observe(bytes[offset + index] & 0xff);
            }
            if (count < 0) {
                evidence.onEof();
            }
            return count;
        }

        private void observe(int value) {
            if (evidence.requestObserved.get()) {
                return;
            }
            if (value == '\n') {
                inspectLine();
                lineBuffer.reset();
                discardUntilNewline = false;
                return;
            }
            if (discardUntilNewline || value == '\r') {
                return;
            }
            if (lineBuffer.size() >= MAX_INITIALIZE_FRAME_BYTES) {
                lineBuffer.reset();
                discardUntilNewline = true;
                return;
            }
            lineBuffer.write(value);
        }

        @SuppressWarnings("unchecked")
        private void inspectLine() {
            if (lineBuffer.size() == 0 || evidence.requestObserved.get()) {
                return;
            }
            try {
                Map<String, Object> frame = McpJsonDefaults.getMapper().readValue(
                        lineBuffer.toString(StandardCharsets.UTF_8), Map.class);
                if (!"initialize".equals(frame.get("method"))
                        || !(frame.get("params") instanceof Map<?, ?> params)) {
                    return;
                }
                String protocolVersion = safeLabel(
                        String.valueOf(params.get("protocolVersion")), "unknown");
                String clientName = "unknown";
                String clientVersion = "unknown";
                if (params.get("clientInfo") instanceof Map<?, ?> clientInfo) {
                    clientName = safeLabel(String.valueOf(clientInfo.get("name")), "unknown");
                    clientVersion = safeLabel(String.valueOf(clientInfo.get("version")), "unknown");
                }
                evidence.requestedProtocolVersion = protocolVersion;
                evidence.clientName = clientName;
                evidence.clientVersion = clientVersion;
                if (evidence.requestObserved.compareAndSet(false, true)) {
                    log.info("event=mcp.protocol.requested transport=stdio correlationId=none taskIdHash=none "
                                    + "role=server provider=none status=requested elapsedMs=0 fallbackUsed=false "
                                    + "errorClass=NONE circuitState=none tool=none protocolVersion={} "
                                    + "clientName={} clientVersion={}",
                            protocolVersion, clientName, clientVersion);
                }
            } catch (Exception ignored) {
                // The official SDK owns validation and error responses.
            }
        }
    }

    /** Observes the bounded initialize response while forwarding every stdout byte unchanged. */
    private static final class ProtocolObservingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ProtocolEvidence evidence;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(512);
        private boolean discardUntilNewline;

        private ProtocolObservingOutputStream(OutputStream delegate, ProtocolEvidence evidence) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.evidence = Objects.requireNonNull(evidence, "evidence");
        }

        @Override
        public synchronized void write(int value) throws IOException {
            delegate.write(value);
            observe(value & 0xff);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            for (int index = 0; index < length; index++) {
                observe(bytes[offset + index] & 0xff);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            delegate.flush();
        }

        private void observe(int value) {
            if (evidence.negotiatedObserved.get()) {
                return;
            }
            if (value == '\n') {
                inspectLine();
                lineBuffer.reset();
                discardUntilNewline = false;
                return;
            }
            if (discardUntilNewline || value == '\r') {
                return;
            }
            if (lineBuffer.size() >= MAX_INITIALIZE_FRAME_BYTES) {
                lineBuffer.reset();
                discardUntilNewline = true;
                return;
            }
            lineBuffer.write(value);
        }

        @SuppressWarnings("unchecked")
        private void inspectLine() {
            if (lineBuffer.size() == 0 || evidence.negotiatedObserved.get()) {
                return;
            }
            try {
                Map<String, Object> frame = McpJsonDefaults.getMapper().readValue(
                        lineBuffer.toString(StandardCharsets.UTF_8), Map.class);
                if (!(frame.get("result") instanceof Map<?, ?> result)
                        || !(result.get("serverInfo") instanceof Map<?, ?> serverInfo)
                        || !SERVER_NAME.equals(String.valueOf(serverInfo.get("name")))) {
                    return;
                }
                String protocolVersion = safeLabel(
                        String.valueOf(result.get("protocolVersion")), "unknown");
                String responseServerVersion = safeLabel(
                        String.valueOf(serverInfo.get("version")), SERVER_VERSION);
                if (evidence.negotiatedObserved.compareAndSet(false, true)) {
                    log.info("event=mcp.protocol.negotiated transport=stdio correlationId=none taskIdHash=none "
                                    + "role=server provider=none status=negotiated elapsedMs=0 fallbackUsed=false "
                                    + "errorClass=NONE circuitState=none tool=none protocolVersion={} "
                                    + "requestedProtocolVersion={} clientName={} clientVersion={} serverVersion={}",
                            protocolVersion,
                            evidence.requestedProtocolVersion,
                            evidence.clientName,
                            evidence.clientVersion,
                            responseServerVersion);
                }
            } catch (Exception ignored) {
                // The response bytes are forwarded unchanged; diagnostics are best-effort only.
            }
        }
    }

    private static final class ProtocolEvidence {
        private final AtomicBoolean requestObserved = new AtomicBoolean();
        private final AtomicBoolean negotiatedObserved = new AtomicBoolean();
        private final AtomicBoolean eofObserved = new AtomicBoolean();
        private final Runnable eofHandler;
        private volatile String requestedProtocolVersion = "unknown";
        private volatile String clientName = "unknown";
        private volatile String clientVersion = "unknown";

        private ProtocolEvidence(Runnable eofHandler) {
            this.eofHandler = Objects.requireNonNull(eofHandler, "eofHandler");
        }

        private void onEof() {
            if (eofObserved.compareAndSet(false, true)) {
                eofHandler.run();
            }
        }
    }
}
