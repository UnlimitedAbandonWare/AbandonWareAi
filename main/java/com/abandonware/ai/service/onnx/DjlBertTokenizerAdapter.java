package com.abandonware.ai.service.onnx;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class DjlBertTokenizerAdapter implements TokenizerAdapter {
    private static final int MAX_TOKENS = 128;

    @Override
    public EncodedTriplet encodePairs(List<String> queries, List<String> docs) {
        int n = Math.min(sizeOf(queries), sizeOf(docs));
        long[][] ids = new long[n][MAX_TOKENS];
        long[][] attn = new long[n][MAX_TOKENS];
        long[][] type = new long[n][MAX_TOKENS];
        for (int i = 0; i < n; i++) {
            encodePair(valueAt(queries, i), valueAt(docs, i), ids[i], attn[i], type[i]);
        }
        return new EncodedTriplet(ids, attn, type);
    }

    private static void encodePair(String query, String doc, long[] ids, long[] attn, long[] type) {
        int pos = 0;
        ids[pos] = 101L;
        attn[pos] = 1L;
        pos++;
        pos = encodeText(query, ids, attn, type, pos, 0L);
        if (pos < ids.length) {
            ids[pos] = 102L;
            attn[pos] = 1L;
            pos++;
        }
        pos = encodeText(doc, ids, attn, type, pos, 1L);
        if (pos < ids.length) {
            ids[pos] = 102L;
            attn[pos] = 1L;
        }
    }

    private static int encodeText(String text, long[] ids, long[] attn, long[] type, int pos, long tokenType) {
        if (text == null || text.isBlank()) {
            return pos;
        }
        String[] tokens = text.trim().split("\\s+");
        for (String token : tokens) {
            if (pos >= ids.length - 1) {
                break;
            }
            ids[pos] = 1_000L + Math.floorMod(token.toLowerCase(java.util.Locale.ROOT).hashCode(), 20_000);
            attn[pos] = 1L;
            type[pos] = tokenType;
            pos++;
        }
        return pos;
    }

    private static int sizeOf(List<String> values) {
        return values == null ? 0 : values.size();
    }

    private static String valueAt(List<String> values, int index) {
        return values == null || index < 0 || index >= values.size() ? "" : values.get(index);
    }
}
