package com.example.lms.service.onnx.tokenizer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;



/**
 * Minimal WordPiece tokenizer for BERT-style models. Given a vocab file,
 * it tokenises text into wordpieces, prepends [CLS], inserts [SEP] between
 * sequences and pads/truncates to the specified maximum sequence length.
 *
 * <p>If the vocab cannot be loaded, {@link #encodePair(String, String, int)}
 * will return empty arrays, signalling the caller to fall back to a lexical
 * scorer.</p>
 */
public class WordpieceTokenizer implements CrossEncoderTokenizer {
    private static final String CLS = "[CLS]";
    private static final String SEP = "[SEP]";
    private static final String PAD = "[PAD]";
    private static final String UNK = "[UNK]";

    private final Map<String, Integer> vocab = new HashMap<>();

    /**
     * Construct a tokenizer given an input stream for the vocab. Each line
     * should contain a single token. If {@code vocabStream} is null or fails
     * to read, the vocab will be empty and encoding will yield empty arrays.
     */
    public WordpieceTokenizer(InputStream vocabStream) {
        if (vocabStream == null) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(vocabStream, StandardCharsets.UTF_8))) {
            String line; int idx = 0;
            while ((line = br.readLine()) != null) {
                String token = line.trim();
                if (!token.isEmpty()) {
                    vocab.put(token, idx++);
                }
            }
        } catch (Exception ignore) {
            // ignore and leave vocab empty
        }
    }

    @Override
    public Encoded encodePair(String q, String d, int maxSeqLen) {
        // If vocab is empty, return empty arrays to signal fallback
        if (vocab.isEmpty()) {
            return new Encoded(new int[0], new int[0], new int[0]);
        }
        // Basic whitespace tokenisation and lowercasing
        List<Integer> ids = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        // [CLS]
        ids.add(id(CLS));
        types.add(0);
        // Query tokens
        for (String token : basicTokens(q)) {
            for (String wp : wordpiece(token)) {
                ids.add(id(wp));
                types.add(0);
            }
        }
        // [SEP]
        ids.add(id(SEP));
        types.add(0);
        // Document tokens
        for (String token : basicTokens(d)) {
            for (String wp : wordpiece(token)) {
                ids.add(id(wp));
                types.add(1);
            }
        }
        // Final [SEP]
        ids.add(id(SEP));
        types.add(1);
        // Truncate or pad
        int size = Math.min(ids.size(), maxSeqLen);
        int[] inputIds = new int[maxSeqLen];
        int[] attn = new int[maxSeqLen];
        int[] tt = new int[maxSeqLen];
        for (int i = 0; i < maxSeqLen; i++) {
            if (i < size) {
                inputIds[i] = ids.get(i);
                attn[i] = 1;
                tt[i] = types.get(i);
            } else {
                inputIds[i] = id(PAD);
                attn[i] = 0;
                tt[i] = 0;
            }
        }
        return new Encoded(inputIds, attn, tt);
    }

    /**
     * Map a token to its ID. Returns the ID of [UNK] if not present.
     */
    private int id(String tok) {
        return vocab.getOrDefault(tok, vocab.getOrDefault(UNK, 0));
    }

    /**
     * Basic whitespace tokenisation and punctuation removal.
     */
    private List<String> basicTokens(String s) {
        if (s == null) return Collections.emptyList();
        String cleaned = s.toLowerCase(Locale.ROOT).replaceAll("\\p{Punct}+", " ").trim();
        if (cleaned.isEmpty()) return Collections.emptyList();
        return Arrays.asList(cleaned.split("\\s+"));
    }

    /**
     * Wordpiece tokenisation. If no subword in vocab matches, returns [UNK].
     */
    private List<String> wordpiece(String token) {
        if (vocab.containsKey(token)) {
            return Collections.singletonList(token);
        }
        List<String> result = new ArrayList<>();
        int start = 0;
        boolean allUnknown = true;
        while (start < token.length()) {
            int end = token.length();
            String cur = null;
            while (start < end) {
                String sub = (start == 0 ? token.substring(start, end) : "##" + token.substring(start, end));
                if (vocab.containsKey(sub)) {
                    cur = sub;
                    break;
                }
                end--;
            }
            if (cur == null) {
                // no matching subword; treat as unknown and stop
                result.add(UNK);
                allUnknown = false;
                break;
            }
            result.add(cur);
            allUnknown = false;
            start += cur.startsWith("##") ? cur.length() - 2 : cur.length();
        }
        if (result.isEmpty() || allUnknown) {
            return Collections.singletonList(UNK);
        }
        return result;
    }
}