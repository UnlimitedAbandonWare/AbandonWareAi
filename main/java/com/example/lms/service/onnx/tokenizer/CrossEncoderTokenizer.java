package com.example.lms.service.onnx.tokenizer;


/**
 * Tokeniser interface for cross-encoder models. It encodes a query/document pair
 * into three parallel sequences of token IDs, attention mask and token type IDs.
 */
public interface CrossEncoderTokenizer {

    /** Encoded representation of a tokenised pair. */
    record Encoded(int[] inputIds, int[] attentionMask, int[] tokenTypeIds) {}

    /**
     * Encode a query and document into input IDs, attention mask and token type IDs
     * limited to {@code maxSeqLen}. Implementations may truncate longer inputs.
     *
     * @param q         the query string
     * @param d         the document string
     * @param maxSeqLen maximum sequence length for the output arrays
     * @return an {@link Encoded} containing the three sequences
     */
    Encoded encodePair(String q, String d, int maxSeqLen);
}