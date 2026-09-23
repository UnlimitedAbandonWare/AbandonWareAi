package com.example.lms.service.rag.graph;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRagPortMappingConnectorTest {

    @Test
    void mapsOutputPortToInputPortWithHashOnlyNodeIdentity() {
        KgChunk.KgRelation relation = GraphRagPortMappingConnector.connect(
                "Alpha ownerToken=secret",
                "semantic output",
                "Beta api_key=secret",
                "semantic input",
                "supports",
                "supports",
                0.8d,
                "sourceHashOnly");

        Map<String, Object> summary = GraphRagPortMappingConnector.publicSummary(relation);

        assertEquals("semantic_output", summary.get("sourcePort"));
        assertEquals("semantic_input", summary.get("targetPort"));
        assertEquals("SUPPORTS", summary.get("relationKind"));
        assertEquals("SUPPORTS", summary.get("connectorKind"));
        assertEquals(12, String.valueOf(summary.get("connectorHash12")).length());
        assertEquals(12, String.valueOf(summary.get("sourceNodeHash12")).length());
        assertEquals(12, String.valueOf(summary.get("targetNodeHash12")).length());
        assertFalse(summary.toString().contains("ownerToken=secret"));
        assertFalse(summary.toString().contains("api_key=secret"));
        assertTrue(relation.connectorHash12().matches("[0-9a-f]{12}"));
    }
}
