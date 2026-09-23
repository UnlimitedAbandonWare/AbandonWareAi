package com.abandonware.ai.service.onnx;

import java.util.List;

public interface TokenizerAdapter {
    class EncodedTriplet {
        public final long[][] ids, attn, type;
        public EncodedTriplet(long[][] ids, long[][] attn, long[][] type) { this.ids = ids; this.attn = attn; this.type = type; }
    }
    EncodedTriplet encodePairs(List<String> queries, List<String> docs);
}