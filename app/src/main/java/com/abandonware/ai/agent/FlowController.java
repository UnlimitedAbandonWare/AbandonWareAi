package com.abandonware.ai.agent;

import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolContextFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.FlowController
 * Role: controller
 * Key Endpoints: POST /flows/{flow}:run, ANY /flows/flows
 * Dependencies: com.abandonware.ai.agent.orchestrator.Orchestrator, com.abandonware.ai.agent.tool.request.ToolContext, com.abandonware.ai.agent.tool.request.ToolContextFactory
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.FlowController
role: controller
api:
  - POST /flows/{flow}:run
  - ANY /flows/flows
*/
public class FlowController {

    private final Orchestrator orchestrator;
    private final ToolContextFactory contextFactory;

    @Autowired
    public FlowController(Orchestrator orchestrator, ToolContextFactory contextFactory) {
        this.orchestrator = orchestrator;
        this.contextFactory = contextFactory;
    }

    /**
     * Executes the specified flow with the provided input.  Additional
     * parameters can be supplied via the request body.  If the optional
     * {@code trace} query parameter is set to {@code on} then the returned
     * map will include a {@code trace} field containing a list of step
     * invocations.
     *
     * @param flow the flow name (without the .yaml suffix)
     * @param input the input payload for the flow
     * @param trace whether to enable debug tracing ("on" or "off")
     * @return the final state map from the orchestrator, optionally augmented with trace information
     */
    @PostMapping("/{flow}:run")
    public Map<String, Object> run(@PathVariable("flow") String flow,
                                   @RequestBody Map<String, Object> input,
                                   @RequestParam(name = "trace", defaultValue = "off") String trace) {
        // Construct a ToolContext from the current request.  Pass through the roomId
        // if provided so tools like KakaoPushTool can target the correct channel.
        String roomId = input != null ? (String) input.getOrDefault("roomId", "n/a") : "n/a";
        ToolContext ctx = contextFactory.fromCurrent(Map.of("roomId", roomId));
        if ("on".equalsIgnoreCase(trace)) {
            ctx = ctx.withDebugTrace(true);
        }
        return orchestrator.execute(flow, input, ctx);
    }
}