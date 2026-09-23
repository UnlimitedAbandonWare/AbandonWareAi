package com.abandonwareai.telemetry.compaction;

import org.springframework.stereotype.Component;

@Component
public class SimHash64Reducer {
    public long hash64(String s){ return s==null?0L:s.hashCode(); }

}