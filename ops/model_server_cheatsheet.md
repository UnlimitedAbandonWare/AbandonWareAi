# Local model options for 2×RTX 3060 (12GB each) — 64GB RAM

Below are safe presets for **8K** and **32K** context. Pick ONE stack.

---

## Option A — vLLM (tensor-parallel across both GPUs, AWQ/GPTQ)

**Pros:** High throughput, OpenAI-compatible server.  
**Cons:** Quantized weights required; >7B models still memory-hungry.

### 8K preset
```bash
# Example with Qwen2-7B-Instruct-AWQ (8K)
python -m vllm.entrypoints.openai.api_server \
  --model Qwen/Qwen2-7B-Instruct-AWQ \
  --quantization awq \
  --tensor-parallel-size 2 \
  --max-model-len 8192 \
  --gpu-memory-utilization 0.90 \
  --host 0.0.0.0 --port 8000
```

### 32K preset
```bash
# Use a long-context variant or RoPE-scaled model (AWQ/GPTQ)
python -m vllm.entrypoints.openai.api_server \
  --model meta-llama/Llama-3.1-8B-Instruct \
  --tensor-parallel-size 2 \
  --max-model-len 32768 \
  --enable-chunked-prefill \
  --gpu-memory-utilization 0.90 \
  --dtype auto \
  --port 8000
```
> Tip: If OOM occurs, try a 4-bit GPTQ/AWQ checkpoint (e.g., `*GPTQ-4bit`), reduce `--max-model-len`, or raise CPU offload via `--swap-space 32`.

---

## Option B — llama.cpp (GGUF, heavy GPU offload)

**Pros:** Very lean; GGUF Q4\_K\_M fits easily; stable for 8–16K.  
**Cons:** 32K needs RoPE scaling models; speed lower than vLLM.

### 8K preset
```bash
llama-server -m ./models/Llama-3.1-8B-Instruct-Q4_K_M.gguf \
  -ngl 999 -c 8192 -t 10 --mlock --host 0.0.0.0 --port 8000
```

### 32K preset
```bash
# Use a long-context GGUF (RoPE-scaled 32K)
llama-server -m ./models/Llama-3.1-8B-Instruct-32K-Q4_K_M.gguf \
  -ngl 999 -c 32768 -t 10 --mlock --host 0.0.0.0 --port 8000
```
> If KV cache OOM appears, lower `-c` (e.g., 24K) or add `--no-mmap` + faster storage.

---

## Option C — Text Generation Inference (TGI)

```bash
docker run --gpus all -p 8080:80 ghcr.io/huggingface/text-generation-inference:2.1.0 \
  --model-id mistralai/Mistral-7B-Instruct-v0.3 \
  --num-shard 2 --max-input-tokens 8192
```

---

### Recommended picks (balanced for 2×12GB)
- **8K:** `Qwen2-7B-Instruct-AWQ` (vLLM) or `Llama-3.1-8B-Instruct-Q4_K_M.gguf` (llama.cpp)
- **32K:** Long-context `Llama-3.1-8B` or `Mistral-Nemo-Instruct-2407` (if a 32K variant/checkpoint is available)
- Set your app to call the server at `http://localhost:8000/v1` (OpenAI API shim) and use:
  - `llm.engine=jlama` for Java-inproc
  - or point the HTTP client to the runtime above for remote inference.
