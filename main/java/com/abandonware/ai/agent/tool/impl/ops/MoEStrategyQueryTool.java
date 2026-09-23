package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.artplate.ArtPlateRegistry;
import com.example.lms.artplate.ArtPlateSpec;
import com.example.lms.artplate.NineArtPlateGate;
import com.example.lms.moe.RgbStrategySelector;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class MoEStrategyQueryTool implements AgentTool {
    private final ObjectProvider<RgbStrategySelector> selectorProvider;
    private final ObjectProvider<NineArtPlateGate> plateGateProvider;
    private final ObjectProvider<ArtPlateRegistry> plateRegistryProvider;

    public MoEStrategyQueryTool(ObjectProvider<RgbStrategySelector> selectorProvider,
                                ObjectProvider<NineArtPlateGate> plateGateProvider,
                                ObjectProvider<ArtPlateRegistry> plateRegistryProvider) {
        this.selectorProvider = selectorProvider;
        this.plateGateProvider = plateGateProvider;
        this.plateRegistryProvider = plateRegistryProvider;
    }

    @Override
    public String id() {
        return "moe.strategy.query";
    }

    @Override
    public String description() {
        return "Return the latest MoE strategy and ArtPlate selection state without changing strategy.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        RgbStrategySelector selector = selectorProvider == null ? null : selectorProvider.getIfAvailable();
        NineArtPlateGate plateGate = plateGateProvider == null ? null : plateGateProvider.getIfAvailable();
        ArtPlateRegistry registry = plateRegistryProvider == null ? null : plateRegistryProvider.getIfAvailable();
        RgbStrategySelector.Decision decision = selector == null ? null : selector.getLastDecision();
        ArtPlateSpec selected = plateGate == null ? null : plateGate.getLastSelected();
        int plateCount = registry == null ? 0 : registry.all().size();

        TraceStore.put("tool.moe.strategy.query.available", selector != null);
        TraceStore.put("tool.moe.strategy.query.hasDecision", decision != null);
        TraceStore.put("tool.moe.strategy.query.plateCount", plateCount);
        TraceStore.put("tool.moe.strategy.query.selectedPlate",
                selected == null ? "none" : SafeRedactor.traceLabelOrFallback(selected.id(), "unknown"));
        TraceStore.put("tool.moe.strategy.query.status", selector == null ? "SKIPPED" : "OK");
        if (selector == null) {
            TraceStore.put("tool.moe.strategy.query.skipped.reason", "MOE_SELECTOR_UNAVAILABLE");
        } else if (decision == null) {
            TraceStore.put("tool.moe.strategy.query.skipped.reason", "NO_MOE_DECISION_YET");
        } else {
            TraceStore.put("tool.moe.strategy.query.skipped.reason", null);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", selector != null);
        out.put("hasDecision", decision != null);
        out.put("plateCount", plateCount);
        out.put("selectedPlate", selected == null ? "none" : SafeRedactor.traceLabelOrFallback(selected.id(), "unknown"));
        if (decision != null) {
            out.put("primaryStrategy", decision.primaryStrategy() == null ? "unknown" : decision.primaryStrategy().name());
            out.put("fallbackStrategies", decision.fallbackStrategies() == null
                    ? List.of()
                    : decision.fallbackStrategies().stream().map(Enum::name).toList());
            out.put("reasons", decision.reasons() == null
                    ? List.of()
                    : decision.reasons().stream()
                    .map(reason -> SafeRedactor.traceLabelOrFallback(reason == null ? null : reason.tag(), "unknown"))
                    .toList());
            if (decision.scoreCard() != null) {
                out.put("scoreCard", scoreCard(decision.scoreCard()));
            }
        }
        return ToolResponse.ok().put("moeStrategy", out);
    }

    private static Map<String, Object> scoreCard(RgbStrategySelector.ScoreCard scoreCard) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("redScore", scoreCard.redScore());
        out.put("greenScore", scoreCard.greenScore());
        out.put("blueScore", scoreCard.blueScore());
        out.put("rankedCandidateCount", scoreCard.rankedCandidates() == null ? 0 : scoreCard.rankedCandidates().size());
        return out;
    }
}
