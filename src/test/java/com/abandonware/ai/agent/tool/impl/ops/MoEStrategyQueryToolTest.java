package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.example.lms.artplate.ArtPlateRegistry;
import com.example.lms.artplate.NineArtPlateGate;
import com.example.lms.artplate.PlateContext;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoEStrategyQueryToolTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void selectedPlateBelongsToCurrentRequestTrace() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry());
        gate.decide(new PlateContext(false, true, 0, 0, 0.5d, false,
                0.3d, 0.4d, 0.1d, 0.2d));
        TraceStore.clear();
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("gate", gate));
        MoEStrategyQueryTool tool = new MoEStrategyQueryTool(null,
                beans.getBeanProvider(NineArtPlateGate.class), null);

        assertEquals("not_observed", selectedPlate(tool));
        TraceStore.put("artplate.selector.selected", "AP3_VEC_DENSE");
        assertEquals("AP3_VEC_DENSE", selectedPlate(tool));
    }

    private static Object selectedPlate(MoEStrategyQueryTool tool) {
        Map<?, ?> result = (Map<?, ?>) tool.execute(new ToolRequest(Map.of(), null))
                .data().get("moeStrategy");
        return result.get("selectedPlate");
    }
}
