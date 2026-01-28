package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.AgentTool
 * Role: config
 * Dependencies: com.abandonware.ai.agent.tool.request.ToolRequest, com.abandonware.ai.agent.tool.response.ToolResponse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.AgentTool
role: config
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