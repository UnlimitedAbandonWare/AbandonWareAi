package com.abandonware.ai.telemetry;

import java.util.*;
/** CFVM-Raw - stores raw error patterns as 'tiles' for later retrieval. */
public class CfvmRaw {
    private final List<Map<String,Object>> rawTiles = new ArrayList<>();
    public void record(Map<String,Object> raw) { rawTiles.add(raw); }
    public List<Map<String,Object>> tiles() { return rawTiles; }
}