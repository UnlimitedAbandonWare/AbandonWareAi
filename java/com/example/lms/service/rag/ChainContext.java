package com.example.lms.service.rag;

import dev.langchain4j.rag.query.Query;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



/**
 * A context object that is passed through the retrieval chain to accumulate
 * debugging information, warnings and errors.  The context carries the
 * original query, a session identifier and arbitrary options which may be
 * interpreted by handlers.  Callers should use the helper methods
 * {@link #warn(String)}, {@link #err(String)} and {@link #dbg(String, Object)}
 * rather than mutating the underlying collections directly.
 */
public class ChainContext {
    /**
     * An opaque identifier representing the conversation session.  When
     * provided downstream handlers may consult session scoped services such
     * as memory or history loaders.  The identifier is treated as a string
     * to avoid accidental truncation of large numeric values.
     */
    private String sessionId;

    /**
     * The LangChain4j query associated with this retrieval cycle.  Handlers
     * should avoid mutating the query directly; rather they should create
     * derived queries when performing additional searches.
     */
    private Query query;

    /**
     * Arbitrary options which may influence handler behaviour.  For example
     * handlers may honour keys such as <code>useWebSearch</code> or
     * <code>officialOnly</code> when determining which external sources to
     * consult.  Values are stored as objects but generally expected to be
     * primitive wrappers or strings.
     */
    private Map<String, Object> opts;

    /**
     * Collected warning messages.  These are advisory in nature and do not
     * necessarily indicate that the chain has failed.  Examples include
     * falling back to heuristic keyword extraction or skipping a handler due
     * to missing dependencies.
     */
    private List<String> warnings;

    /**
     * Collected error messages.  Unlike warnings these indicate that a
     * handler encountered an unexpected exception or was unable to perform
     * its primary function.  The chain itself should remain robust in the
     * face of such errors, logging them via this collection rather than
     * throwing.
     */
    private List<String> errors;

    /**
     * Arbitrary debugging information.  Handlers may record any useful
     * intermediate state here.  The values are opaque to the chain but
     * callers may inspect them after retrieval has completed.  For example
     * a handler may store a list of search queries or similarity scores.
     */
    private Map<String, Object> debug;

    /**
     * Default constructor for frameworks.
     */
    public ChainContext() {
        this(null, null, new HashMap<>());
    }

    /**
     * Construct a new context with the supplied session identifier, query
     * and options.  Warning, error and debug collections are created empty.
     *
     * @param sessionId the session identifier (may be null)
     * @param query     the current query (must not be null)
     * @param opts      arbitrary options (may be null)
     */
    public ChainContext(String sessionId, Query query, Map<String, Object> opts) {
        this.sessionId = sessionId;
        this.query = query;
        this.opts = (opts != null) ? new HashMap<>(opts) : new HashMap<>();
        this.warnings = new ArrayList<>();
        this.errors = new ArrayList<>();
        this.debug = new HashMap<>();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Query getQuery() {
        return query;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public Map<String, Object> getOpts() {
        return opts;
    }

    public void setOpts(Map<String, Object> opts) {
        this.opts = opts;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getErrors() {
        return errors;
    }

    public Map<String, Object> getDebug() {
        return debug;
    }

    /**
     * Record a warning message in the context.  Warnings are intended to
     * communicate non-fatal issues to higher layers.
     *
     * @param m the warning message
     */
    public void warn(String m) {
        if (m != null) {
            warnings.add(m);
        }
    }

    /**
     * Record an error message in the context.  Errors are used to capture
     * unexpected conditions that handlers chose not to throw.
     *
     * @param m the error message
     */
    public void err(String m) {
        if (m != null) {
            errors.add(m);
        }
    }

    /**
     * Record a key/value pair in the debug map.  Keys are overwritten on
     * repeated calls.
     *
     * @param k the debug key
     * @param v the debug value
     */
    public void dbg(String k, Object v) {
        if (k != null) {
            debug.put(k, v);
        }
    }
}