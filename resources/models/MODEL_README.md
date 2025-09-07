# Cross‑Encoder Model

This directory contains the ONNX model artifacts used by the project’s local
cross‑encoder reranker.  The model implements a **cross‑encoder** architecture
that jointly encodes a query–document pair and produces a single relevance
score.  It serves as a drop‑in replacement for external API calls and allows
the reranker to operate entirely on the host machine.

## Files

| File                                      | Description                                                   |
|-------------------------------------------|---------------------------------------------------------------|
| `your-cross-encoder.onnx`                 | The serialized ONNX model containing the cross‑encoder neural network. |
| `your-cross-encoder.onnx.sha256`          | A SHA‑256 checksum of the ONNX file.  The application verifies this checksum at startup to detect corruption. |
| `vocab.txt`                               | (Optional) Vocabulary for the WordPiece tokenizer.  When present the tokenizer will initialise with this vocabulary; otherwise tokenisation will fall back to whitespace tokenisation. |

## Input and Output Tensors

The model accepts three inputs:

1. **`input_ids`** – A tensor of shape `[1, maxSeqLen]` containing integer token IDs representing the concatenated query and document.  The sequence is padded or truncated to `maxSeqLen` tokens.
2. **`attention_mask`** – A tensor of the same shape indicating which tokens are real and which are padding.  A value of `1` marks a real token and `0` marks padding.
3. **`token_type_ids`** – A tensor indicating segment IDs (0 for the query portion and 1 for the document portion).

The model produces a single output:

* **`logits`** – A tensor of shape `[1]` containing a raw score.  This score may be converted to a probability via a sigmoid transform when the `normalize` flag is enabled in `application.yml`.

## Licence and Source

This model is provided for demonstration purposes only.  It was derived from a
publicly available cross‑encoder model suitable for reranking tasks.  You
should replace it with a model appropriate for your domain and ensure that you
comply with the licence terms of the original model.