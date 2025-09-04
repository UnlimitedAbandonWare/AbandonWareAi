package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Routing interface for selecting chat models based on heuristic signals or
 * primitive hints.  Implementations should decide which underlying model
 * to use and optionally provide an escalation path when confidence is low.
 */
public interface ModelRouter {

    /**
     * Route using a full {@link RouteSignal}.  Implementations may combine
     * complexity, uncertainty and other metrics encoded in the signal to
     * decide between different chat models.
     *
     * @param sig the routing signal
     * @return the selected chat model
     */
    ChatModel route(RouteSignal sig);

    /**
     * Overloaded route method accepting primitive hints.  Many call sites
     * rely on this signature to avoid constructing a full signal.  The
     * implementation should translate the intent/risk/verbosity/targetMaxTokens
     * parameters into a reasonable {@link RouteSignal} and delegate to
     * {@link #route(RouteSignal)}.
     *
     * @param intent the high‑level intent (e.g. GENERAL, PAIRING)
     * @param riskLevel a qualitative risk indicator (e.g. LOW, HIGH)
     * @param verbosityHint a hint describing the desired verbosity
     * @param targetMaxTokens the maximum token budget for the response
     * @return the selected chat model
     */
    ChatModel route(String intent,
                    String riskLevel,
                    String verbosityHint,
                    Integer targetMaxTokens);

    /**
     * Escalate to a more capable model based on the provided signal.  When
     * evidence is insufficient or uncertainty is high, callers should invoke
     * this method to obtain a higher tier model.  The default implementation
     * may ignore the signal and simply return the highest tier model.
     *
     * @param sig the routing signal
     * @return the escalated chat model
     */
    ChatModel escalate(RouteSignal sig);

    /**
     * Resolve the name of the underlying chat model.  This is used for
     * logging and diagnostics purposes to avoid exposing internal classes.
     *
     * @param model the chat model
     * @return the user‑friendly model name
     */
    String resolveModelName(ChatModel model);
}