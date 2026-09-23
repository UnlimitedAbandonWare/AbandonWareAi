/* Only guard-approved assistant text belongs here; run/session state stays in chat.js. */
(function (root) {
  "use strict";
  const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function safeLink(value, base) {
    if (!value || !String(value).trim()) return null;
    try {
      const url = new URL(String(value || ""), base);
      return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password ? url.href : null;
    } catch { return null; }
  }

  function render(target, value) {
    const source = String(value ?? "");
    const doc = target.ownerDocument;
    // Rendering support must never turn a readable answer into an empty/error bubble.
    if (!root.marked?.Marked || !root.DOMPurify?.sanitize || source.length > 200000) {
      target.textContent = source;
      return { mode: "plain" };
    }
    const math = [];
    const mathToken = (raw, tex, display) => ({ type: display ? "answerBlockMath" : "answerInlineMath", raw, tex, display });
    const mathPlaceholder = token => {
      const index = math.push(token) - 1;
      return '<span data-chat-math="' + index + '"></span>';
    };
    try {
      const parser = new root.marked.Marked({ gfm: true, breaks: true, async: false });
      parser.use({
        renderer: {
          html(token) {
            const raw = token.text || token.raw || "";
            return /^<!--[\s\S]*-->$/.test(raw.trim()) ? "" : escapeHtml(raw);
          },
          image(token) {
            // A model-produced URL must not cause an automatic remote image request.
            const label = "이미지: " + (token.text || "링크");
            return '<a href="' + escapeHtml(token.href) + '">' + escapeHtml(label) + '</a>';
          }
        },
        extensions: [{
          name: "answerBlockMath", level: "block",
          start(src) { const index = src.search(/\$\$|\\\[/); return index < 0 ? undefined : index; },
          tokenizer(src) {
            const match = /^(?:\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\])[ \t]*(?:\n|$)/.exec(src);
            return match ? mathToken(match[0], match[1] ?? match[2], true) : undefined;
          },
          renderer: mathPlaceholder
        }, {
          name: "answerInlineMath", level: "inline",
          start(src) { const index = src.search(/\$(?!\$)|\\\(/); return index < 0 ? undefined : index; },
          tokenizer(src) {
            const match = /^(?:\$(?!\$)([^\n$]+?)\$(?!\$)|\\\(([^\n]+?)\\\))/.exec(src);
            return match ? mathToken(match[0], match[1] ?? match[2], false) : undefined;
          },
          renderer: mathPlaceholder
        }]
      });
      const fragment = root.DOMPurify.sanitize(parser.parse(source), {
        RETURN_DOM_FRAGMENT: true,
        ALLOWED_TAGS: ["p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "em",
          "del", "blockquote", "ul", "ol", "li", "pre", "code", "a", "hr",
          "table", "thead", "tbody", "tr", "th", "td", "span"],
        ALLOWED_ATTR: ["href", "title", "start", "data-chat-math"],
        ALLOW_DATA_ATTR: false
      });
      for (const anchor of fragment.querySelectorAll("a")) {
        const href = safeLink(anchor.getAttribute("href"), doc.baseURI);
        if (href) {
          anchor.setAttribute("href", href);
          anchor.setAttribute("rel", "noopener noreferrer");
          anchor.setAttribute("target", "_blank");
        } else {
          anchor.removeAttribute("href");
        }
      }
      for (const placeholder of fragment.querySelectorAll("[data-chat-math]")) {
        const token = math[Number(placeholder.getAttribute("data-chat-math"))];
        placeholder.removeAttribute("data-chat-math");
        if (!token) { placeholder.remove(); continue; }
        placeholder.className = token.display ? "answer-math-display" : "answer-math-inline";
        if (!root.katex?.renderToString || token.tex.length > 4096) {
          placeholder.textContent = token.raw;
          continue;
        }
        try {
          const rendered = root.katex.renderToString(token.tex, {
            displayMode: token.display, output: "mathml", throwOnError: true,
            trust: false, strict: "error", maxExpand: 200, maxSize: 10,
            macros: Object.create(null), globalGroup: false
          });
          placeholder.replaceChildren(root.DOMPurify.sanitize(rendered, {
            RETURN_DOM_FRAGMENT: true,
            USE_PROFILES: { html: true, mathMl: true },
            FORBID_TAGS: ["img", "a", "svg", "style", "script", "iframe"],
            FORBID_ATTR: ["href", "src", "xlink:href", "style"]
          }));
        } catch {
          placeholder.textContent = token.raw;
        }
      }
      target.replaceChildren(fragment);
      target.classList.add("chat-answer-body");
      return { mode: "markdown" };
    } catch {
      target.textContent = source;
      return { mode: "plain" };
    }
  }
  root.ChatAnswerRenderer = Object.freeze({ render });
})(typeof globalThis !== "undefined" ? globalThis : this);

