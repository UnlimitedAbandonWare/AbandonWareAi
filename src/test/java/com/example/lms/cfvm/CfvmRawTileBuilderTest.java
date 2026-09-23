package com.example.lms.cfvm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfvmRawTileBuilderTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void builderRecordsCondensedRawTileWithoutRawTracePayload() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.add(0x12L, 3L, 4L);

        Map<String, Object> out = new CfvmRawTileBuilder().build("abc123", buffer);

        assertEquals("cfvm_raw_tile", out.get("kind"));
        assertEquals(Boolean.TRUE, out.get("enabled"));
        assertNull(out.get("disabledReason"));
        assertEquals("abc123", out.get("patternId"));
        assertEquals(1, out.get("bufferSize"));
        assertEquals(1, out.get("slotCount"));
        assertEquals(Boolean.FALSE, out.get("rawPayloadStored"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) out.get("slots");
        assertEquals(1, slots.size());
        assertEquals("12", slots.get(0).get("patternId"));
        assertEquals(3L, slots.get(0).get("traceSize"));
        assertEquals(4L, slots.get(0).get("signatureLength"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.rawTile.enabled"));
        assertNull(TraceStore.get("cfvm.rawTile.disabledReason"));
        assertEquals("abc123", TraceStore.get("cfvm.rawTile.patternId"));
        assertEquals(1, TraceStore.get("cfvm.rawTile.bufferSize"));
        assertEquals(1, TraceStore.get("cfvm.rawTile.slotCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.rawTile.condensed"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.rawTile.rawPayloadStored"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("Authorization"));
        assertFalse(String.valueOf(out).contains("Authorization"));
    }

    @Test
    void builderDisablesOnlyWhenNoRawMatrixEntriesExist() {
        Map<String, Object> out = new CfvmRawTileBuilder().build("abc123", null);

        assertEquals("cfvm_raw_tile", out.get("kind"));
        assertEquals(Boolean.FALSE, out.get("enabled"));
        assertEquals(CfvmRawTileBuilder.DISABLED_REASON, out.get("disabledReason"));
        assertEquals(0, out.get("bufferSize"));
        assertTrue(!out.containsKey("slots"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.rawTile.enabled"));
        assertEquals(CfvmRawTileBuilder.DISABLED_REASON, TraceStore.get("cfvm.rawTile.disabledReason"));
        assertEquals(0, TraceStore.get("cfvm.rawTile.bufferSize"));
    }

    @Test
    void builderPreservesNineSlotPartitionAsCondensedDiagnostics() {
        RawMatrixBuffer buffer = new RawMatrixBuffer(9, 0.35d);
        for (int i = 0; i < 9; i++) {
            buffer.add(0x100L + i, 10L + i, 20L + i);
            buffer.updateWeight(i, i == 6 ? 0.95d : 0.10d + (i * 0.01d));
        }

        Map<String, Object> out = new CfvmRawTileBuilder().build("abc123", buffer);

        assertEquals("cfvm_raw_tile", out.get("kind"));
        assertEquals(9, out.get("bufferSize"));
        assertEquals(9, out.get("slotCount"));
        assertEquals(6, out.get("dominantSlot"));
        assertEquals(Boolean.FALSE, out.get("rawPayloadStored"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) out.get("slots");
        assertEquals(9, slots.size());
        assertEquals(0, slots.get(0).get("slot"));
        assertEquals(8, slots.get(8).get("slot"));
        assertEquals(9, TraceStore.get("cfvm.rawTile.bufferSize"));
        assertEquals(9, TraceStore.get("cfvm.rawTile.slotCount"));
        assertEquals(6, TraceStore.get("cfvm.rawTile.dominantSlot"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.rawTile.condensed"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.rawTile.rawPayloadStored"));
    }
}
