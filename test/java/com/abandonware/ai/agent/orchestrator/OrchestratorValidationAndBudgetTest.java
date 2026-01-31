package com.abandonware.ai.agent.orchestrator;

import com.abandonware.ai.agent.flow.FlowDefinition;
import com.abandonware.ai.agent.flow.StepType;
import com.abandonware.ai.agent.policy.ToolPolicyEnforcer;
import com.abandonware.ai.agent.orchestrator.support.BudgetGuard;
import com.abandonware.ai.agent.orchestrator.support.SchemaRegistry;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.errors.BudgetExceededException;
import com.abandonware.ai.agent.errors.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;




import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrchestratorValidationAndBudgetTest {

    @Mock SchemaRegistry schemaRegistry;
    @Mock BudgetGuard budgetGuard;
    @Mock ToolPolicyEnforcer policy;

    ObjectMapper om = new ObjectMapper();

    Orchestrator orchestrator;

    @BeforeEach
    void setUp() {
        this.orchestrator = new Orchestrator(schemaRegistry, budgetGuard, policy, om);
        when(budgetGuard.allow(anyString(), anyDouble(), anyLong())).thenReturn(true);
    }

    @Test
    void toolArgs_invalidSchema_shouldThrowValidation() {
        var schema = mock(JsonSchema.class);
        when(schemaRegistry.schemaFor("web.search")).thenReturn(schema);
        when(schema.validate(any())).thenReturn(Set.of(ValidationMessage.of("q", "type", "q must be string")));

        var flow = flowWithSingleTool("web.search", Map.of("q", 123));
        assertThatThrownBy(() ->
                orchestrator.run(flow, Map.of(), ToolContext.of("sess-1")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("web.search");
    }

    @Test
    void budgetGuard_blocksLargeEstimates() {
        when(budgetGuard.allow(anyString(), anyDouble(), anyLong())).thenReturn(false);
        var flow = flowWithSingleTool("llm.call", Map.of("prompt", "x".repeat(20000)));
        assertThatThrownBy(() ->
                orchestrator.run(flow, Map.of(), ToolContext.of("sess-2")))
            .isInstanceOf(BudgetExceededException.class);
    }

    @Test
    void policyHooks_calledBeforeAndAfter() {
        when(schemaRegistry.schemaFor(any())).thenReturn(null);
        orchestrator.setToolRunner((toolId, args, ctx) -> Map.of("ok", true));
        var flow = flowWithSingleTool("ping", Map.of("text", "ok"));
        orchestrator.run(flow, Map.of(), ToolContext.of("sess-3").withDebugTrace(true));
        verify(policy).beforeCall(eq("ping"), anyMap(), any());
        verify(policy).afterCall(eq("ping"), any());
    }

    private static FlowDefinition flowWithSingleTool(String toolId, Map<String, Object> args) {
        var step = new FlowDefinition.Step();
        step.setId("s1");
        step.setType(StepType.TOOL);
        step.setUses(toolId);
        step.setArgs(args);
        var def = new FlowDefinition();
        def.setName("test-flow");
        def.setSteps(List.of(step));
        return def;
    }
}