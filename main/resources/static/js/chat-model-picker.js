/* Model choices are server-owned; browser preferences never grant availability. */
(function (root) {
  const PAGE = 80;

  function createPicker(doc, env) {
    const panel = doc.getElementById("modelBrowser");
    const select = doc.getElementById("modelSelect");
    if (!panel || !select) return null;
    const search = panel.querySelector("[data-model-search]");
    const filter = panel.querySelector("[data-model-filter]");
    const list = panel.querySelector("[data-model-results]");
    const status = panel.querySelector("[data-model-catalog-status]");
    const countLine = panel.querySelector("[data-model-count]");
    const moreButton = panel.querySelector("[data-model-more]");
    const closer = panel.querySelector("[data-model-close]");
    const opener = doc.getElementById("modelBrowserTrigger");
    const isDialog = String(panel.tagName || panel.tag || "").toUpperCase() === "DIALOG";
    const storage = env.storage;
    const fetchImpl = env.fetch;
    let choices = [];
    let publicCatalogLoaded = false;
    let favorites = readIds("chat.modelFavorites");
    let recent = readIds("chat.recentModels");
    let catalogTicket = 0;
    let catalogCurrent = false;
    let windowSize = PAGE;
    let windowKey = "";

    function readIds(key) {
      try {
        const value = JSON.parse(storage.getItem(key) || "[]");
        return Array.isArray(value) ? value.filter(id => typeof id === "string").slice(0, 20) : [];
      } catch { return []; }
    }
    function save(key, ids) {
      try { storage.setItem(key, JSON.stringify(ids)); } catch { /* optional */ }
    }
    const reasons = {
      remote_selection_disabled: "원격 모델 선택이 꺼져 있습니다",
      cloud_manifest_disabled: "서버 카탈로그에서 비활성화됨",
      route_not_configured: "API 연결 필요",
      auth_missing: "API 연결 필요",
      health_unavailable: "최근 연결 점검 실패",
      capability_not_observed: "채팅 기능 확인 필요",
      remote_model_requires_route: "원격 제공업체 연결 필요",
      protocol_unsupported: "호출 방식 미지원"
    };
    function ready() {
      const selected = choices.find(row => row.id === select.value);
      doc.dispatchEvent(new CustomEvent("chat:model-catalog", { detail: { ready: catalogCurrent && selected?.selectable === true } }));
    }
    function remember(id) {
      recent = [id, ...recent.filter(value => value !== id)].slice(0, 10);
      save("chat.recentModels", recent);
    }
    function hasClass(node, name) {
      if (!node) return false;
      if (node.classList && typeof node.classList.contains === "function") return node.classList.contains(name);
      return String(node.className || "").split(/\s+/).includes(name);
    }
    function focusedMarker() {
      const active = doc.activeElement;
      if (!active || typeof list.contains !== "function" || !list.contains(active)) return null;
      if (hasClass(active, "model-favorite")) {
        return { kind: "favorite", id: active.getAttribute("data-model-id") };
      }
      if (active.getAttribute && active.getAttribute("data-model-more") === "1") return { kind: "more" };
      return null;
    }
    function focusMarker(marker) {
      if (!marker) return;
      if (marker.kind === "favorite") {
        for (const button of list.querySelectorAll(".model-favorite")) {
          if (button.getAttribute("data-model-id") === marker.id) {
            button.focus();
            return;
          }
        }
        if (search && typeof search.focus === "function") search.focus();
        return;
      }
      const more = list.querySelector("[data-model-more]");
      if (more) {
        more.focus();
        return;
      }
      const choiceButtons = list.querySelectorAll(".model-choice-select");
      const last = choiceButtons[choiceButtons.length - 1];
      if (last) last.focus();
    }
    function render() {
      const query = search.value.trim().toLowerCase();
      const mode = filter.value;
      const key = mode + "\0" + query;
      if (key !== windowKey) {
        windowKey = key;
        windowSize = PAGE;
      }
      let marker = focusedMarker();
      const scrollTop = list.scrollTop;
      const visible = choices.filter(row => (!query || (row.provider + " " + row.modelId).toLowerCase().includes(query))
        && (mode !== "available" || row.selectable)
        && (mode !== "unavailable" || !row.selectable)
        && (mode !== "favorites" || favorites.includes(row.id))
        && (mode !== "recent" || recent.includes(row.id)));
      visible.sort((a, b) => mode === "recent" ? recent.indexOf(a.id) - recent.indexOf(b.id)
        : Number(favorites.includes(b.id)) - Number(favorites.includes(a.id)));
      const shown = visible.slice(0, windowSize);
      list.replaceChildren();
      for (const row of shown) {
        const item = doc.createElement("div");
        item.className = "model-choice";
        const choice = doc.createElement("button");
        choice.type = "button";
        choice.className = "model-choice-select";
        choice.disabled = !row.selectable;
        choice.setAttribute("data-model-id", row.id);
        choice.setAttribute("aria-pressed", String(row.id === select.value));
        const title = doc.createElement("strong");
        title.textContent = row.modelId;
        const info = doc.createElement("small");
        const lifecycle = row.release === "preview" ? " · Preview" : row.release === "stable" ? " · Stable" : "";
        info.textContent = row.provider + lifecycle + " · " + (row.selectable
          ? row.status === "installed" ? "설치됨 · 생성 미검증" : "연결 설정됨 · 생성 미검증"
          : reasons[row.reason] || "연결 또는 모델 점검 필요");
        choice.append(title, info);
        choice.addEventListener("click", () => {
          select.value = row.id;
          select.dispatchEvent(new Event("change", { bubbles: true }));
          closePanel();
          select.focus();
        });
        const favorite = doc.createElement("button");
        favorite.type = "button";
        favorite.className = "model-favorite";
        favorite.setAttribute("data-model-id", row.id);
        const isFavorite = favorites.includes(row.id);
        favorite.textContent = isFavorite ? "★" : "☆";
        favorite.setAttribute("aria-label", row.modelId + " 즐겨찾기");
        favorite.setAttribute("aria-pressed", String(isFavorite));
        favorite.addEventListener("click", () => {
          favorites = isFavorite ? favorites.filter(id => id !== row.id) : [row.id, ...favorites].slice(0, 20);
          save("chat.modelFavorites", favorites);
          render();
        });
        item.append(choice, favorite);
        if (!row.selectable) {
          const help = doc.createElement("button");
          help.type = "button";
          help.className = "model-help";
          help.textContent = "이유";
          help.setAttribute("aria-expanded", "false");
          help.setAttribute("aria-label", row.modelId + " 연결 이유");
          help.addEventListener("click", () => {
            const open = help.getAttribute("aria-expanded") !== "true";
            help.setAttribute("aria-expanded", String(open));
            let note = item.querySelector(".model-help-note");
            if (!open) {
              if (note) note.textContent = "";
              return;
            }
            if (!note) {
              note = doc.createElement("p");
              note.className = "model-help-note";
              item.appendChild(note);
            }
            note.textContent = (reasons[row.reason] || "연결 또는 모델 점검 필요")
              + ". 운영자 설정에서 연결을 확인하세요. 이 목록에서는 다운로드나 유료 호출을 시작하지 않습니다.";
          });
          item.append(help);
        }
        list.appendChild(item);
      }
      if (!visible.length) {
        const empty = doc.createElement("p");
        empty.textContent = "조건에 맞는 모델이 없습니다.";
        list.appendChild(empty);
      }
      const truncated = visible.length > shown.length;
      const windowLabel = truncated ? "검색 결과 " + visible.length + "개 중 " + shown.length + "개 표시" : "";
      if (countLine && countLine.textContent !== windowLabel) countLine.textContent = windowLabel;
      if (moreButton) {
        const moreHadFocus = doc.activeElement === moreButton;
        moreButton.hidden = !truncated;
        moreButton.textContent = truncated ? "모델 더 보기 · " + windowLabel : "모델 더 보기";
        if (!truncated && moreHadFocus) marker = marker || { kind: "more" };
      } else if (truncated) {
        const more = doc.createElement("button");
        more.type = "button";
        more.className = "model-choice-select";
        more.setAttribute("data-model-more", "1");
        more.textContent = "모델 더 보기 · " + windowLabel;
        more.addEventListener("click", () => {
          windowSize += PAGE;
          render();
        });
        list.appendChild(more);
      }
      focusMarker(marker);
      if (marker) list.scrollTop = scrollTop;
    }
    function closePanel() {
      if (!panel.open) return;
      if (typeof panel.close === "function") panel.close();
      else panel.open = false;
    }
    function openPanel() {
      if (panel.open) return;
      if (isDialog && typeof panel.showModal === "function") panel.showModal();
      else panel.open = true;
      if (search && typeof search.focus === "function") search.focus();
    }
    async function refresh(discover = false) {
      const ticket = ++catalogTicket;
      catalogCurrent = false;
      status.textContent = "설치 모델과 연결 상태 확인 중…";
      doc.dispatchEvent(new CustomEvent("chat:model-catalog", { detail: { ready: false } }));
      try {
        const response = await fetchImpl("/api/chat/models" + (discover ? "?discover=true" : ""), { credentials: "same-origin", cache: "no-store" });
        if (ticket !== catalogTicket) return;
        if (!response.ok || response.redirected || !(response.headers.get("content-type") || "").includes("application/json")) {
          throw new Error("catalog_unavailable");
        }
        const rows = await response.json();
        if (ticket !== catalogTicket) return;
        if (!Array.isArray(rows)) throw new Error("catalog_invalid");
        choices = rows.filter(row => row && typeof row.id === "string" && typeof row.modelId === "string"
          && typeof row.provider === "string" && typeof row.selectable === "boolean").slice(0, 1100);
        publicCatalogLoaded = Boolean(discover);
        catalogCurrent = true;
        const previous = select.value;
        select.replaceChildren();
        const groups = new Map();
        for (const row of choices.filter(row => row.selectable)) {
          let group = groups.get(row.provider);
          if (!group) {
            group = doc.createElement("optgroup");
            group.label = row.provider;
            groups.set(row.provider, group);
            select.appendChild(group);
          }
          const option = doc.createElement("option");
          option.value = row.id;
          option.textContent = row.modelId;
          group.appendChild(option);
        }
        if (!choices.some(row => row.id === previous && row.selectable)) {
          const unavailable = doc.createElement("option");
          unavailable.value = previous;
          unavailable.textContent = previous ? previous + " · 사용 가능 여부 확인 필요" : "모델을 선택해 주세요";
          unavailable.disabled = true;
          unavailable.selected = true;
          select.prepend(unavailable);
        } else {
          select.value = previous;
        }
        status.textContent = choices.filter(row => row.selectable).length + "개 선택 가능 · 목록 확인은 실제 생성 성공을 뜻하지 않습니다.";
        render();
        ready();
      } catch {
        if (ticket !== catalogTicket) return;
        status.textContent = choices.length
          ? "모델 상태를 새로 확인하지 못했습니다. 이전 목록을 유지합니다."
          : "모델 상태를 확인하지 못했습니다. 서버 연결 후 다시 확인해 주세요.";
        doc.dispatchEvent(new CustomEvent("chat:model-catalog", { detail: { ready: false } }));
      }
    }
    if (moreButton) moreButton.addEventListener("click", () => { windowSize += PAGE; render(); });
    if (opener) opener.addEventListener("click", openPanel);
    if (closer) closer.addEventListener("click", () => {
      closePanel();
      if (opener && typeof opener.focus === "function") opener.focus();
    });
    panel.addEventListener("cancel", event => {
      if (event && typeof event.preventDefault === "function") event.preventDefault();
      closePanel();
      if (opener && typeof opener.focus === "function") opener.focus();
    });
    panel.addEventListener("keydown", event => {
      if (event.key !== "Tab" || !panel.open) return;
      const controls = Array.from(panel.querySelectorAll("button, input, select, [tabindex]"))
        .filter(element => !element.disabled && element.tabIndex !== -1 && element.getClientRects().length > 0);
      const first = controls[0], last = controls[controls.length - 1];
      if (!first) { event.preventDefault(); return; }
      if (event.shiftKey && doc.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && doc.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    });
    search.addEventListener("input", render);
    filter.addEventListener("change", () => {
      render();
      if (!publicCatalogLoaded && ["all", "unavailable"].includes(filter.value)) void refresh(true);
    });
    panel.querySelector("[data-model-refresh]").addEventListener("click", () => refresh(["all", "unavailable"].includes(filter.value)));
    select.addEventListener("change", () => { remember(select.value); ready(); render(); });
    doc.addEventListener("keydown", event => {
      if (event.key !== "Escape" || !panel.open) return;
      const inside = typeof panel.contains === "function" && (panel.contains(event.target) || event.target === opener);
      if (!inside) return;
      if (event.cancelable && typeof event.preventDefault === "function") event.preventDefault();
      closePanel();
      const home = opener || panel.querySelector("summary") || select;
      if (home && typeof home.focus === "function") home.focus();
    });
    if (env.autostart !== false) void refresh(false);
    return { refresh, render };
  }

  if (typeof module === "object" && module.exports) module.exports = { createPicker };
  if (root.document && typeof root.document.getElementById === "function" && root.document.getElementById("modelBrowser")) {
    createPicker(root.document, { fetch: root.fetch, storage: root.localStorage });
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
