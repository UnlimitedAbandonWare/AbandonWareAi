package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;



/**
 * Base contract for all agent tools.  A tool is a unit of capability that
 * can be invoked by the language model through the orchestrator.  Tools
 * encapsulate their identifier, a human-readable description and an
 * execution method.  Implementations are expected to be annotated with
 * {@link com.abandonware.ai.agent.tool.annotations.RequiresScopes} when a
 * particular permission is required before invocation.
 */
public interface AgentTool {

    /**
     * Returns the unique identifier for this tool.  The identifier must
     * exactly match the id defined in the tool manifest (e.g. "kakao.push").
     */
    String id();

    /**
     * A short description of what the tool does.  This is surfaced to the
     * language model to aid selection.
     */
    String description();

    /**
     * Executes the tool with the supplied request.  Implementations should
     * perform any necessary validation of the input payload and may throw
     * exceptions to signal failure.  If the tool is annotated with
     * {@link com.abandonware.ai.agent.tool.annotations.RequiresScopes} then the
     * {@link com.abandonware.ai.agent.consent.ConsentService} will have
     * verified the required scopes prior to invocation.
     *
     * @param request the request containing input parameters and context
     * @return a response containing arbitrary output data
     * @throws Exception if an unexpected error occurs during execution
     */
    ToolResponse execute(ToolRequest request) throws Exception;
}