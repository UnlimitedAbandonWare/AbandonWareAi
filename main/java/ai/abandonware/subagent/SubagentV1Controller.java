package ai.abandonware.subagent;

import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolContextFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Exact HTTP entry point for the production subagent flow. */
@RestController
public final class SubagentV1Controller {

    private final Orchestrator orchestrator;
    private final ToolContextFactory contextFactory;

    public SubagentV1Controller(Orchestrator orchestrator, ToolContextFactory contextFactory) {
        this.orchestrator = orchestrator;
        this.contextFactory = contextFactory;
    }

    @PostMapping("/flows/subagent.v1:run")
    public Map<String, Object> run(@RequestBody Map<String, Object> input,
                                   @RequestParam(name = "trace", defaultValue = "off") String trace) {
        ToolContext context = contextFactory.fromCurrent(Map.of("roomId", roomId(input)));
        if ("on".equalsIgnoreCase(trace)) {
            context = context.withDebugTrace(true);
        }
        return orchestrator.execute("subagent.v1", input, context);
    }

    private static String roomId(Map<String, Object> input) {
        Object value = input == null ? null : input.get("roomId");
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? "n/a" : text;
    }
}
