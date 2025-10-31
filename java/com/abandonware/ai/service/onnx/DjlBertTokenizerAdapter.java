package com.abandonware.ai.service.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class DjlBertTokenizerAdapter implements TokenizerAdapter {
    private final HuggingFaceTokenizer tok = HuggingFaceTokenizer.newInstance("bert-base-uncased");

    @Override
    public EncodedTriplet encodePairs(List<String> queries, List<String> docs) {
        int n = queries.size();
        List<long[]> ids = new ArrayList<>(), attn = new ArrayList<>(), type = new ArrayList<>();
        for (int i=0;i<n;i++) {
            var enc = tok.encode(queries.get(i), docs.get(i));
            ids.add(enc.getIds());
            attn.add(enc.getAttentionMask());
            type.add(enc.getTypeIds());
        }
        return new EncodedTriplet(ids.toArray(long[][]::new), attn.toArray(long[][]::new), type.toArray(long[][]::new));
    }
}
