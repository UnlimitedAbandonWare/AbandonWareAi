// /js/model-strategy.js
(function () {
  "use strict";

  // ── 0) 레거시 키 → 신규 키 이관 ───────────────────────────────────────────
  try {
    const legacyActive   = localStorage.getItem("model.active");
    const legacyStrategy = localStorage.getItem("model.strategy");
    if (legacyActive && !localStorage.getItem("models.active")) {
      localStorage.setItem("models.active", legacyActive);
    }
    if (legacyStrategy && !localStorage.getItem("models.strategy")) {
      localStorage.setItem("models.strategy", legacyStrategy);
    }
  } catch {}

  const isPrerender = () =>
    typeof document.prerendering === "boolean" && document.prerendering;

  const getInitialModels = () => {
    // 1) 서버 템플릿 주입
    try {
      const fromInitial = (window.initialData?.models ?? []).map(m => ({
        id: m.modelId,
        label: m.modelId,
        features: (m.features || "").toLowerCase(),
        ctx: m.ctx || 0,
        release: 0,
      }));
      if (fromInitial.length) return fromInitial;
    } catch {}
    // 2) 옵션 셀렉트에서 추출
    try {
      const sel = document.getElementById("defaultModelSelect");
      if (sel?.options?.length) {
        return [...sel.options]
          .filter(o => o.value)
          .map(o => ({
            id: o.value,
            label: o.textContent.trim(),
            features: "",
            ctx: 0,
            release: 0,
          }));
      }
    } catch {}
    // 3) 캐시 폴백
    try {
      return JSON.parse(localStorage.getItem("models.cache") || "[]");
    } catch {
      return [];
    }
  };

  const uniqById = (list) => {
    const seen = new Set();
    return list.filter(x => (x && !seen.has(x.id) && seen.add(x.id)));
  };

  const scoreModel = (m) => {
    const isMoe =
      /\b(moe|mixture[- ]?of[- ]?experts|mixture)\b/.test(m.features) ||
      /\bmoe\b/i.test(m.label);
    const moeBoost = isMoe ? 100 : 0;
    const ctxScore = Math.min(m.ctx || 0, 256000) / 1000;
    return moeBoost + ctxScore;
  };

  const pickTopMoe = (models) =>
    models.slice().sort((a, b) => scoreModel(b) - scoreModel(a))[0] || null;

  async function persistDefaultModel(modelId) {
    try {
      await fetch("/api/settings/model", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ model: modelId, modelId, defaultModel: modelId }),
      });
    } catch {}
  }

  // ── 1) fetch 오버라이드: backend-default 아닐 때만 모델 주입 ──────────────
  (function patchFetch() {
    const orig = window.fetch;
    window.fetch = async (input, init = {}) => {
      const u = typeof input === "string" ? input : (input?.url || "");
      const path = new URL(u, location.href).pathname;

      const active =
        localStorage.getItem("models.active") ||
        window.initialData?.currentModel ||
        "";
      const strategy = localStorage.getItem("models.strategy") || "auto-moe";

      if (/^\/api\/chat(\/stream)?$/.test(path) && active && strategy !== "backend-default") {
        const headers = new Headers(init.headers || {});
        headers.set("X-Model-Override", active);
        init.headers = headers;

        if (init.body && (headers.get("Content-Type") || "").includes("application/json")) {
          try {
            const obj = JSON.parse(init.body);
            obj.modelId ??= active;
            obj.model ??= active;
            obj.defaultModel ??= active;
            init.body = JSON.stringify(obj);
          } catch {}
        }
      }
      return orig(input, init);
    };
  })();

  // ── 2) UI 초기화 ──────────────────────────────────────────────────────────
  async function initUI() {
    const $picker     = document.getElementById("modelPicker");
    const $strategy   = document.getElementById("modelStrategySelect");
    const $activeText = document.getElementById("activeModelText");
    if (!$picker || !$strategy || !$activeText) return;

    const models = uniqById(getInitialModels());
    if (models.length) {
      localStorage.setItem("models.cache", JSON.stringify(models));
    }

    // 드롭다운 채우기
    $picker.innerHTML = "";
    (models.length
      ? models
      : [{
          id: (window.initialData?.currentModel || "gpt-5-chat-latest"),
          label: (window.initialData?.currentModel || "gpt-5-chat-latest"),
        }]
    ).forEach(m => {
      const o = document.createElement("option");
      o.value = m.id;
      o.textContent = m.label || m.id;
      $picker.appendChild(o);
    });

    // 전략 기본값: active 있으면 manual, 없으면 auto-moe
    let strategy = localStorage.getItem("models.strategy");
    if (!strategy) {
      strategy = localStorage.getItem("models.active") ? "manual" : "auto-moe";
      localStorage.setItem("models.strategy", strategy);
    }

    // active 시드: localStorage → 서버값 → 첫 옵션
    let active =
      localStorage.getItem("models.active") ||
      window.initialData?.currentModel ||
      ($picker.options[0]?.value || "");

    // backend-default가 아니라면 최소 1회 active 저장(초기화 방지)
    if (strategy !== "backend-default" && active && !localStorage.getItem("models.active")) {
      localStorage.setItem("models.active", active);
    }

    if (strategy === "auto-moe") {
      const top = pickTopMoe(models);
      if (top) {
        active = top.id;
        localStorage.setItem("models.active", active);
        await persistDefaultModel(active);
      } else if (active) {
        // 리스트가 비어도 fallback active 보존
        localStorage.setItem("models.active", active);
      }
    } else if (strategy === "manual") {
      if ($picker.value) {
        // 저장된 active 우선, 없으면 현재 셀렉트
        active = localStorage.getItem("models.active") || $picker.value;
        localStorage.setItem("models.active", active);
        await persistDefaultModel(active);
      }
    } else {
      // backend-default: active 비움(헤더 미주입)
      active = "";
      localStorage.setItem("models.active", "");
    }

    // UI 반영
    $strategy.value = strategy;
    $picker.value   = active || $picker.value;
    $activeText.textContent = active || "(backend default)";

    // 이벤트
    $strategy.addEventListener("change", async () => {
      localStorage.setItem("models.strategy", $strategy.value);
      await initUI(); // 전략 변경 즉시 재적용
    });

    $picker.addEventListener("change", async () => {
      localStorage.setItem("models.strategy", "manual");
      localStorage.setItem("models.active", $picker.value);
      await persistDefaultModel($picker.value);
      document.getElementById("activeModelText").textContent = $picker.value;
      document.getElementById("modelStrategySelect").value = "manual";
    });
  }

  if (isPrerender()) {
    document.addEventListener(
      "visibilitychange",
      () => { if (!isPrerender()) initUI(); },
      { once: true }
    );
  } else {
    initUI();
  }

  // 외부 조회용 헬퍼
  window.__modelStrategy = {
    getActiveModel: () => localStorage.getItem("models.active") || ""
  };
})();