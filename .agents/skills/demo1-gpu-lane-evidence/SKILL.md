# Demo1 GPU Lane Evidence (DESKTOP-M5NOV6K, RTX 3060 + RTX 3090)

Dual-GPU Ollama lane work: routing chat/fast/embed roles to the intended card,
proving which GPU actually ran a request, and separating "resident model" from
"generating tokens" from "answer held".

## When

- Symptoms like "3060 바쁜데 3090 유휴", "3090에 VRAM만 남음", "모델이 안 도는 것 같다",
  duplicate `ollama serve` instances, `CUDA_VISIBLE_DEVICES` pinning, or any
  patch touching `LocalLlmProcessManager`, `application-llm.yaml`,
  `application-desktop-gpu-node.yml`, `application-local-llm.yml`, or
  `configs/models.manifest.yaml` GPU lane defaults.

## Verification contract (one request, end to end)

A fix is proven only when a single observed request connects ALL of:

1. selected role/model (`llm` / `llm.fast` / `embedding` / llmrouter lane)
2. effective endpoint actually used (not the yaml role comment — the resolved
   `base-url` after env expansion)
3. port owner PID on that endpoint (`Get-NetTCPConnection` → `OwningProcess`)
4. runner/serve PID → GPU UUID (`nvidia-smi --query-compute-apps`, or the
   serve process env `CUDA_VISIBLE_DEVICES`; UUID, never index)
5. actual generation on that GPU (util spike during request, or Ollama
   `load_duration`/`eval_count`/`eval_duration` fields preserved from response)
6. final outcome: answer text rendered, or the exact `reasonCode`/guard that
   produced HOLD/DEGRADE (`RagControlPresentationBoundary`/`RagActionPlan`)

"Port responds" ≠ "runs on the intended GPU" ≠ "model generated" ≠ "answer
reached the screen". Keep them as separate fields in any report. **All six are
mandatory** — any `not_observed` field means the goal is not done, and adjacent
fixes (warmup split, citation, retry tuning, compile/focused-test green) are
supporting evidence only, never completion.

## Hardware facts (this box, verified 2026-09-24)

- `nvidia-smi` index **0 = RTX 3060**, index **1 = RTX 3090** — Task Manager
  labels are REVERSED (its "GPU 0" is the 3090). Never copy an index into
  `CUDA_VISIBLE_DEVICES`; use the UUID from `nvidia-smi --query-gpu=uuid`.
- The 3060 is the display GPU: ~30 desktop/browser/OBS/agent processes attach
  to it normally — idle-ish 3060 util is not an Ollama symptom by itself.
- WDDM: `nvidia-smi` per-process memory can show `N/A` — that is an
  observation limit, never rewrite it as 0 or "not on GPU".
- Resident VRAM (`ollama ps` / `/api/ps` `size_vram`) = model kept by
  `keep_alive`; it is not proof of generation.

## Live findings recorded 2026-09-24 (re-probe before reuse — stale fast)

- 3 `ollama serve` instances observed: `11434` (tray-app child), `11435` and
  **`11438`** (cmd children). `11438` is outside spec docs (they mention up to
  11437) — identify owner before keeping or stopping it.
- Endpoint defaults collapse: `application-llm.yaml:13/51/219` and
  `application-desktop-gpu-node.yml:37-39` all default to `127.0.0.1:11435`
  regardless of `gpu: rtx3090`/`rtx3060` labels — labels do not pin GPUs.
- Spec drift: `configs/models.manifest.yaml:26` defaults
  `LLM_3090_BASE_URL` to `11434` while `application-llm.yaml:13` defaults it
  to `11435` — resolve against SSOT + live ports, not one file.
- `LocalLlmProcessManager.selectCudaVisibleDevice` (~:679): `valid.size()==1`
  pins the UUID (`auto_discovered_uuid`); `>1` only records
  `auto_discovery_ambiguous` — it neither pins nor refuses; a later path can
  still reuse a responding server on the wrong GPU.
- `application-local-llm.yml:27`: `warmup.model` defaults to
  `${embedding.model:qwen3-embedding:4b}` — a chat `/api/chat` warmup aimed at
  an embedding model. (`warmupEmbedModelExplicit()` already treats the
  yaml-nested `embed-model` default as non-explicit — read the current gate
  before patching.)
- Answer HOLD path: `RagControlProjectionRenderer.heldNotice()` +
  `RagControlPresentationBoundary` `plan.shouldStop()` → fixed notice.
  `RagControlRuntimeAdapter` already maps empty retrieval to DEGRADE, so
  "Evidence 0 → always hold" is NOT the current contract — find which guard
  set `verificationRequired`/the final `reasonCode` before patching.

## Tools

- `scripts/ollama-status-snapshot.ps1` — read-only: serve instances/ports,
  per-port `/api/ps`, GPU util/VRAM, compute-app PID→GPU UUID map, reversal
  heuristic. Run before and after any lane change.
- `scripts/ollama-unload-idle.ps1` — per-model unload after confirm (`-WhatIf`,
  `-Keep`). Never blanket-kill `ollama.exe` (11434 is the tray app's child).
- `scripts/ollama-prefer-3090.ps1` — env/state diagnosis + manual pin recipe
  (`-WriteUserEnv`, `-LaunchPinned` are opt-in).
- `scripts/ollama-light-preload.ps1` — warm light models only
  (`PLACEHOLDER-EMBED-MODEL` must be replaced from `ollama ls` first).
- Canonical lane setup (not replaced by the above):
  `scripts/desktop_dual_ollama_gpu_setup.ps1 -Mode ValidateOnly|Start` +
  `scripts/modules/DesktopDualOllamaGpuSetup.psm1` (UUID pin, port race,
  listener ownership, cold-reboot gate).

## Do not

- `CUDA_VISIBLE_DEVICES=0` — index 0 is the **3060** here. UUID or nothing.
- `ollama rm` a model without a verified reason — re-download is tens of GB.
- Kill `ollama.exe`/`ollama serve` processes as a fix — identify port→client
  wiring first; the tray-owned 11434 may be auto-restarted or break the CLI.
- Conclude "GPU bug" from Task Manager engine-graph names — the graph engine
  label is not the owning process or the API.
- Invent a new GPU manager — reuse the existing UUID pin, lease, and cancel
  paths; fix the ambiguous-discovery and wrong-warmup seams instead.
- Treat a fix as done on config read alone — complete = selected model
  actually generated on the intended GPU AND the answer reached the screen.

## Pairs with

- `$demo1-local-llm-gpu-gateway` (endpoint/model wiring rules)
- `$demo1-mutable-spec-policy` (ports/models are SSOT variables, not constants)
- `$demo1-api-spec-drift-guard` (docs vs live inventory conflicts)
- `agent-prompts/gpu-lane-repair-20260924/brief.md` (current repair directive)
