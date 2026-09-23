# API drift report

generated: 2026-09-17T16:21:21.4522984+09:00
repoRoot: C:\AbandonWare\demo-1\demo-1\src

## Files
- configs/api-routing.yaml present: True
- docs/API_ROUTING_SPEC.md present: True

## Ollama
```
NAME                         ID              SIZE      MODIFIED          
smtek/Qwen3.8-27B:Q3_K_XL    b2f2bd435db0    14 GB     About an hour ago    
qwen3-embedding:4b           df5bd2e3c74c    2.5 GB    4 weeks ago          
qwen3.8:27b                  22130167c4c2    17 GB     4 weeks ago          
gemma4:31b                   6316f0629137    19 GB     6 weeks ago          
qwen3.6:27b                  a50eda8ed977    17 GB     6 weeks ago          
gemma4:12b                   4eb23ef187e2    7.6 GB    6 weeks ago          
qwen3.5:9b                   6488c96fa5fa    6.6 GB    6 weeks ago          
gemma4:latest                c6eb396dbd59    9.6 GB    6 weeks ago          
gemma4:26b                   5571076f3d70    17 GB     4 months ago         
nomic-embed-text:latest      0a109f422b47    274 MB    9 months ago         
qwen3-embedding:latest       64b933495768    4.7 GB    9 months ago         
bge-m3:latest                790764642607    1.2 GB    9 months ago         
qwen3-vl:8b                  901cae732162    6.1 GB    10 months ago
```

## Env key presence (names only)
- OPENAI_API_KEY: present=True length=164
- GROQ_API_KEY: present=True length=56
- GEMINI_API_KEY: present=True length=53
- BRAVE_API_KEY: present=True length=31
- TAVILY_API_KEY: present=True length=41
- SERPAPI_API_KEY: present=True length=64
- NAVER_CLIENT_ID: present=True length=20
- NAVER_CLIENT_SECRET: present=True length=10
- DEEPGRAM_API_KEY: present=True length=40
- SONIOX_API_KEY: present=True length=147
- PINECONE_API_KEY: present=True length=75
- OLLAMA_HOST: present=False length=0
- LOCAL_LLM_ENABLED: present=False length=0

## api-routing.yaml model/alias hints
- L1: # AWX model-defaults remapped 2026-09-17: legacy ollama names -> installed (3090 chat/judge/coder, 3060 fast/vision).
- L10: ollama:
- L15: openai_compat_path: "/v1"
- L23: alias_to_installed:
- L35: base_url: "https://api.search.brave.com/res/v1/web/search"
- L62: - id: ollama_embed
- L64: env: [LLM_BASE_URL, EMBED_BASE_URL, OLLAMA_HOST]
- L66: seams: ["com.example.lms.service.embedding.OllamaEmbeddingModel"]
- L67: - id: openai_embed
- L69: env: [OPENAI_API_KEY]
- L79: - id: ollama_chat
- L81: env: [LLM_BASE_URL, LLM_API_KEY, LLM_CHAT_MODEL]
- L84: - id: ollama_fast
- L86: env: [LLM_FAST_BASE_URL, LLM_FAST_MODEL]
- L88: - id: ollama_vision
- L90: env: [LLM_VISION_BASE_URL, LLM_VISION_MODEL]
- L92: - id: groq
- L94: env: [GROQ_API_KEY]
- L95: base_url: "https://api.groq.com/openai/v1"
- L96: - id: gemini
- L98: env: [GEMINI_API_KEY]
- L99: - id: openai
- L101: env: [OPENAI_API_KEY]
- L102: base_url: "https://api.openai.com/v1"
- L115: fields: [purpose, provider, model, endpointClass, attempt, httpStatus, errorClass, fallbackTo, keyPresent, keySource]

## Agent action
If a skill, comment, or old Codex note names a model/endpoint/field not listed above or rejected by a live probe, update configs/api-routing.yaml and the existing provider seam to the live contract, then fix the stale prose last.
