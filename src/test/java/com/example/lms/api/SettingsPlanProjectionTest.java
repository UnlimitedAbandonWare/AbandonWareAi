package com.example.lms.api;

import com.example.lms.plan.PlanExecutionSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SettingsPlanProjectionTest {
    @Test void conditionalPlanPreservesLegacyDslStatus() {
        var spec = PlanExecutionSpec.parse(Map.of("when", Map.of("any", List.of("true")),
                "pipeline", List.of("prompt.build", "answer.generate")));
        var view = SettingsPlanProjection.fromSpec("not_used", spec, Map.of(), Map.of(),
                new PlanExecutionSpec.StageFlags(false,false,false,false,true));
        assertEquals("not_used", view.legacyDslStatus());
        assertEquals("true", view.whenState());
        assertEquals("delegated", view.stages().get(0).status());
    }
    @Test void delegatedIsNotExecuted() {
        var spec = PlanExecutionSpec.parse(Map.of("pipeline", List.of("prompt.build","answer.generate")));
        var view = SettingsPlanProjection.fromSpec("not_used", spec, Map.of(), Map.of(),
                new PlanExecutionSpec.StageFlags(false,false,false,false,true));
        assertTrue(view.stages().stream().allMatch(s -> s.status().equals("delegated")));
    }
    @Test void unknownDoesNotStartExpansion() {
        var spec = PlanExecutionSpec.parse(Map.of("when", Map.of("any", List.of("metrics.result_count < 3")),
                "pipeline", List.of("analyze.selfAsk")));
        var view = SettingsPlanProjection.fromSpec("not_used", spec, Map.of(), Map.of(),
                new PlanExecutionSpec.StageFlags(true,true,true,true,true));
        assertEquals("unknown", view.whenState());
        assertEquals("skipped_when_inactive", view.stages().get(0).status());
        assertEquals("when_unknown", view.reasonCode());
    }
    @Test void usesExistingPipelineAllowlistAndDoesNotReturnExpressions() throws Exception {
        var meta = new LinkedHashMap<String,Object>();
        meta.put("planDsl","not_used"); meta.put("plan.when","true");
        meta.put("plan.when.any", List.of("PRIVATE_EXPRESSION"));
        meta.put("plan.stageLedger",List.of(Map.of("stage","prompt.build","status","delegated","detail","caller_owned"),
                Map.of("stage","private.stage","status","executed")));
        var view = SettingsPlanProjection.fromMetadata(meta);
        assertEquals(1,view.stages().size());
        assertEquals("true",view.whenState());
        assertEquals("not_used",view.legacyDslStatus());
        assertFalse(new ObjectMapper().writeValueAsString(view).contains("PRIVATE_EXPRESSION"));
    }
}
