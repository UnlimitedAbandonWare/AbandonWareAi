# Local llama.cpp (JNI) quick start

This app includes an in‑process GGUF inference path using **de.kherud:llama**.

## 1) Add a model file
Place a GGUF file under `./models/`, e.g.
```
./models/qwen2.5-0.5b-instruct-Q4_K_M.gguf
```

## 2) Enable the engine
Run with Spring profile `llamacpp` or set these properties:

```properties
llm.engine=llamacpp
llm.model-id=./models/qwen2.5-0.5b-instruct-Q4_K_M.gguf
llm.threads=8
llm.gpu-layers=0   # set >0 only if you replaced the bundled CPU libs with a GPU build
```

> Tip: To use a GPU build, compile `java-llama.cpp` with `-DGGML_CUDA=ON` (or Metal on macOS) and point the JVM to the native library folder:
```
-Dde.kherud.llama.lib.path=/opt/jllama
```

## 3) Call the API
A minimal controller is available:

```
POST /api/llm/generate
Body: { "prompt": "Hello" }
```

## 4) Health
The model is loaded lazily on first request and closed on JVM shutdown.

References:
- kherud/java-llama.cpp README (Quick Start, model & GPU notes)
- Maven Central: de.kherud:llama