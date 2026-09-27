# Live inventory snapshot (2026-09-25 KST)

Machine: DESKTOP-M5NOV6K (`7dd2c567-8c78-46c0-8732-b129fbc48cae`) · Root: `C:\AbandonWare\demo-1\demo-1\src`

## Ollama models (`ollama ls`)

| NAME | ID | SIZE | MODIFIED |
|---|---|---|---|
| smtek/Qwen3.8-27B:Q3_K_XL | b2f2bd435db0 | 14 GB | 7 days ago |
| qwen3-embedding:4b | df5bd2e3c74c | 2.5 GB | 5 weeks ago |
| gemma4:31b | 6316f0629137 | 19 GB | 7 weeks ago |
| qwen3.6:27b | a50eda8ed977 | 17 GB | 7 weeks ago |
| gemma4:12b | 4eb23ef187e2 | 7.6 GB | 7 weeks ago |
| qwen3.5:9b | 6488c96fa5fa | 6.6 GB | 7 weeks ago |
| gemma4:latest | c6eb396dbd59 | 9.6 GB | 7 weeks ago |
| gemma4:26b | 5571076f3d70 | 17 GB | 4 months ago |
| nomic-embed-text:latest | 0a109f422b47 | 274 MB | 10 months ago |
| qwen3-embedding:latest | 64b933495768 | 4.7 GB | 10 months ago |
| bge-m3:latest | 790764642607 | 1.2 GB | 10 months ago |
| qwen3-vl:8b | 901cae732162 | 6.1 GB | 10 months ago |

Delta vs 2026-09-18 snapshot: **removed** `qwen3.8:27b` (17 GB, Q4_K_M) via `ollama rm`
on 2026-09-25 per user directive — VRAM over-occupancy on RTX 3090.
`smtek/Qwen3.8-27B:Q3_K_XL` remains the preferred judge/coder tag.

## Refresh

Re-run `ollama ls` and optional `http://127.0.0.1:11434/api/tags` + `:11435/api/tags`. Replace this file when inventory changes. Never dump API key values.
