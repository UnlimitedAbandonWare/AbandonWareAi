package ai.abandonware.nova.orch.ecosystem;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EcosystemKpiFieldsTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
    }

    @Test
    void putTraceKpiIgnoresNonFiniteNumericTraceValues() {
        TraceStore.put("ecosystem.recirculate.used", Double.POSITIVE_INFINITY);
        TraceStore.put("ecosystem.recirculate.count", Double.POSITIVE_INFINITY);
        TraceStore.put("ecosystem.pool.size", Double.NaN);
        TraceStore.put("ecosystem.ammonia.surgeBlocked", Double.NaN);

        Map<String, Object> kpi = new LinkedHashMap<>();
        EcosystemKpiFields.putTraceKpi(kpi);

        assertEquals(Boolean.FALSE, kpi.get("ecosystem.recirculate.used"));
        assertEquals(0L, kpi.get("ecosystem.recirculate.count"));
        assertEquals(0L, kpi.get("ecosystem.pool.size"));
        assertEquals(Boolean.FALSE, kpi.get("ecosystem.ammonia.surgeBlocked"));
    }
}
