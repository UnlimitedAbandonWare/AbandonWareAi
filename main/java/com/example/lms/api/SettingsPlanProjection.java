package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.plan.PlanExecutionSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Uses the existing tri-state evaluator, ledger and public pipeline allowlist. No execution. */
public final class SettingsPlanProjection {
    private SettingsPlanProjection() {}
    public record View(String legacyDslStatus, Boolean whenPresent, String whenState,
            String postWhenState, Boolean lateActivation, List<ChatStreamEvent.PlanStageSnapshot> stages,
            String reasonCode) {}
    public static View fromMetadata(Map<String,Object> metadata) {
        Map<String,Object> meta = metadata == null ? Map.of() : metadata;
        var snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(meta,null,null,null);
        Object legacy = meta.get("planDsl");
        String dsl = legacy instanceof String s && Set.of("not_used","used","unsupported").contains(s) ? s : null;
        String when = snapshot == null ? null : snapshot.planWhen();
        return new View(dsl, snapshot == null ? null : snapshot.planWhenPresent(), when,
                snapshot == null ? null : snapshot.planWhenPost(),
                snapshot == null ? null : snapshot.planLateActivation(),
                snapshot == null || snapshot.planStages() == null ? List.of() : snapshot.planStages(),
                "unknown".equals(when) ? "when_unknown" : null);
    }
    public static View fromPipeline(com.example.lms.dto.ChatStreamEvent.PipelineSnapshot snapshot){
        if(snapshot==null)return fromMetadata(Map.of());
        return new View(null,snapshot.planWhenPresent(),snapshot.planWhen(),snapshot.planWhenPost(),
                snapshot.planLateActivation(),snapshot.planStages()==null?List.of():List.copyOf(snapshot.planStages()),
                "unknown".equals(snapshot.planWhen())?"when_unknown":null);
    }
    public static View fromSpec(String legacy, PlanExecutionSpec spec, Map<String,Object> scope,
            Map<String,Object> evidence, PlanExecutionSpec.StageFlags flags) {
        var verdict = spec.evaluateWhen(scope);
        var safeFlags = new PlanExecutionSpec.StageFlags(flags.selfAskOn(),flags.biEncoderOn(),
                flags.onnxOn(),flags.diversityOn(),flags.expansionEligible() && verdict.state() == PlanExecutionSpec.TriState.TRUE);
        var meta = new LinkedHashMap<String,Object>();
        meta.put("planDsl",legacy); meta.put("plan.when.present",spec.whenPresent());
        meta.put("plan.when",verdict.state().name().toLowerCase(Locale.ROOT));
        meta.put("plan.stageLedger",spec.stageLedger(evidence,safeFlags).stream().map(PlanExecutionSpec.StageEntry::debugView).toList());
        return fromMetadata(meta);
    }
}
