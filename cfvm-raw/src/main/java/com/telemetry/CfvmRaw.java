package com.telemetry;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.telemetry.CfvmRaw
 * Role: config
 * Feature Flags: telemetry
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.telemetry.CfvmRaw
role: config
flags: [telemetry]
*/
public class CfvmRaw {
    private final List<Map<String,Object>> rawTiles = new ArrayList<>();
    public void record(Map<String,Object> raw) { rawTiles.add(raw); }
    public List<Map<String,Object>> tiles() { return rawTiles; }
}