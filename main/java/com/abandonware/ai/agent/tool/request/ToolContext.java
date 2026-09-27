package com.abandonware.ai.agent.tool.request;

import com.abandonware.ai.agent.consent.ConsentToken;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



/**
 * Encapsulates contextual information about a tool invocation.  In addition
 * to the session identifier and consent token, callers may attach arbitrary
 * key/value pairs to the context.  Tools can use this information to
 * propagate channel metadata, user preferences or other state into their
 * execution logic.
 */
public final class ToolContext {
    private final String sessionId;
    private final ConsentToken consent;
    private final Map<String, Object> extras;
    /**
     * Flag indicating whether debug tracing is enabled for this context.  When
     * true the orchestrator will collect detailed step and tool execution
     * information and include it in the response.  Defaults to {@code false}.
     */
    private final boolean debugTrace;

    public ToolContext(String sessionId, ConsentToken consent) {
        this(sessionId, consent, null, false);
    }

    public ToolContext(String sessionId, ConsentToken consent, Map<String, Object> extras) {
        this(sessionId, consent, extras, false);
    }

    /**
     * Constructs a new {@link ToolContext} with the specified debug trace
     * setting.  This constructor is used internally when toggling debug
     * tracing via {@link #withDebugTrace(boolean)}.
     */
    private ToolContext(String sessionId, ConsentToken consent, Map<String, Object> extras, boolean debugTrace) {
        this.sessionId = sessionId;
        this.consent = consent;
        if (extras == null) {
            this.extras = Collections.emptyMap();
        } else {
            this.extras = Collections.unmodifiableMap(new HashMap<>(extras));
        }
        this.debugTrace = debugTrace;
    }

    /** Returns the current session identifier. */
    public String sessionId() {
        return sessionId;
    }

    /** Returns the consent token associated with this invocation. */
    public ConsentToken consent() {
        return consent;
    }

    /** Returns an unmodifiable map of additional context properties. */
    public Map<String, Object> extras() {
        return extras;
    }

    /**
     * Returns whether debug tracing is enabled on this context.  When enabled
     * the orchestrator will produce a detailed JSON trace of all step and
     * tool executions.
     */
    public boolean debugTrace() {
        return debugTrace;
    }

    /**
     * Returns a new {@link ToolContext} with the specified debug trace flag.
     * The returned context shares the same session id, consent token and
     * extra properties but flips the debug tracing setting.  This method
     * does not modify the current instance.
     *
     * @param enabled whether to enable debug tracing
     * @return a new ToolContext with the desired debug trace state
     */
    public ToolContext withDebugTrace(boolean enabled) {
        return new ToolContext(this.sessionId, this.consent, this.extras, enabled);
    }
}