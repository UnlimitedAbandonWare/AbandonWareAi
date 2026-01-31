package com.abandonware.ai.agent.flow;

import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.tool.request.ToolContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;




import static org.assertj.core.api.Assertions.*;

public class FlowDslExecutorTest {

    @Test
    void when_false_shouldSkipStep() {
        var orchestrator = new Orchestrator();
        orchestrator.setToolRunner((toolId, args, ctx) -> {
            throw new AssertionError("should not be called");
        });

        var step = stepTool("web.search", Map.of("q","hi"));
        step.setWhen("#{ false }");

        var def = flow(step);
        orchestrator.run(def, Map.of(), ToolContext.of("s"));
    }

    @Test
    void parallel_shouldFanoutTools() {
        var calls = new CopyOnWriteArrayList<String>();
        var orchestrator = new Orchestrator();
        orchestrator.setToolRunner((toolId, args, ctx) -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            calls.add(toolId);
            return Map.of("ok",true);
        });

        var s1 = stepTool("t1", Map.of()); s1.setParallel(true);
        var s2 = stepTool("t2", Map.of()); s2.setParallel(true);

        var t0 = System.currentTimeMillis();
        orchestrator.run(flow(s1, s2), Map.of(), ToolContext.of("s"));
        var elapsed = System.currentTimeMillis() - t0;

        assertThat(elapsed).isLessThan(550);
        assertThat(calls).containsExactlyInAnyOrder("t1","t2");
    }

    @Test
    void retry_shouldBackoffAndSucceedOnSecond() {
        var orchestrator = new Orchestrator();
        var counter = new int[]{0};
        orchestrator.setToolRunner((toolId, args, ctx) -> {
            if (++counter[0] == 1) throw new RuntimeException("net");
            return Map.of("ok",true);
        });

        var s = stepTool("flaky", Map.of());
        s.setRetry(new FlowDefinition.Retry(2, 50, FlowDefinition.RetryMode.EXP));

        orchestrator.run(flow(s), Map.of(), ToolContext.of("s"));
        assertThat(counter[0]).isEqualTo(2);
    }

    private static FlowDefinition flow(FlowDefinition.Step... steps){
        var def = new FlowDefinition();
        def.setName("f");
        def.setSteps(List.of(steps));
        return def;
    }
    private static FlowDefinition.Step stepTool(String id, Map<String,Object> args){
        var s = new FlowDefinition.Step();
        s.setId(id);
        s.setType(StepType.TOOL);
        s.setUses(id);
        s.setArgs(args);
        return s;
    }
}