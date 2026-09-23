/* Run in a local browser with the pinned vendor assets and chat-answer-renderer.js. */
(function () {
  const results = [];
  const render = (text) => {
    const node = document.createElement("div");
    document.querySelector("#cases").appendChild(node);
    ChatAnswerRenderer.render(node, text);
    return node;
  };
  const assert = (value, message) => { if (!value) throw new Error(message); };
  function test(name, check) {
    try { check(); results.push({ name, pass: true }); }
    catch (error) { results.push({ name, pass: false, error: error.message }); }
  }
  test("headings lists tables emphasis and code render as document nodes", () => {
    const node = render("### Heading\n\n- **one**\n- two\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n\`\`\`js\nconst a = '<tag>';\n\`\`\`");
    assert(node.querySelector("h3") && node.querySelectorAll("li").length === 2, "heading/list");
    assert(node.querySelector("strong") && node.querySelector("table td"), "emphasis/table");
    assert(node.querySelector("pre code").textContent.includes("<tag>"), "literal code");
  });
  test("raw HTML cannot create executable or interactive nodes", () => {
    const node = render('<img src="https://invalid.example/pixel" onerror="throw 1"><script>throw 1</script><iframe src="/"></iframe>');
    assert(!node.querySelector("img,script,iframe"), "raw HTML became active");
    assert(!node.querySelector("[onerror]"), "event attribute");
  });
  test("Markdown images become explicit links without image loading", () => {
    const node = render("![diagram](https://example.com/diagram.png)");
    assert(!node.querySelector("img"), "automatic image");
    assert(node.querySelector("a").textContent.includes("diagram"), "image link absent");
  });
  test("unsafe links never receive an actionable href", () => {
    for (const href of ["javascript:alert(1)", "data:text/html,bad", "file:///etc/passwd", "https://user:pass@example.com"]) {
      const node = render("[link](" + href + ")");
      assert(!node.querySelector("a[href]"), "unsafe href: " + href.split(":")[0]);
    }
  });
  test("safe links preserve text and prevent opener access", () => {
    const node = render("[reference](https://example.com/reference)");
    const anchor = node.querySelector("a");
    assert(anchor.href === "https://example.com/reference", "safe URL changed");
    assert(anchor.rel.includes("noopener") && anchor.rel.includes("noreferrer"), "opener protection");
  });
  test("inline and block LaTeX render as native MathML", () => {
    const node = render("Inline $x^2$.\n\n$$\\frac{a}{b}$$\n");
    assert(node.querySelectorAll("math").length === 2, "math nodes missing");
    assert(node.querySelector(".answer-math-display mfrac"), "fraction missing");
  });
  test("escaped-parenthesis math delimiters also render", () => {
    const node = render("Value \\(x+y\\).\n\n\\[\\sum_{i=1}^n i\\]\n");
    assert(node.querySelectorAll("math").length === 2, "backslash delimiters missing");
  });
  test("code fences keep math syntax literal", () => {
    const node = render("\`\`\`tex\n$$x^2$$\n\`\`\`");
    assert(!node.querySelector("math") && node.querySelector("code").textContent.includes("$$x^2$$"), "code interpreted");
  });
  test("partial math remains readable then final rendering matches restored rendering", () => {
    const node = render("Before $\\frac{a}");
    assert(node.textContent.includes("Before"), "partial answer lost");
    const final = "Before $\\frac{a}{b}$ after.";
    ChatAnswerRenderer.render(node, final);
    const restored = render(final);
    assert(node.innerHTML === restored.innerHTML && node.querySelector("mfrac"), "final/restore mismatch");
  });
  test("invalid and untrusted math cannot discard surrounding answer or add URLs", () => {
    const node = render("Before $\\unknowncommand{x}$ after.\n\n$$\\href{javascript:alert(1)}{x}$$\n");
    assert(node.textContent.includes("Before") && node.textContent.includes("after."), "answer lost");
    assert(!node.querySelector("[href],[src],script,iframe"), "untrusted math created a URL");
  });
  test("recursive macros are bounded and retain a readable fallback", () => {
    const node = render("Before $\\def\\a{\\a}\\a$ after.");
    assert(node.textContent.includes("Before") && node.textContent.includes("after."), "macro affected full answer");
    assert(!node.querySelector("math"), "recursive macro unexpectedly rendered");
  });
  test("missing parser retains readable plain text", () => {
    const saved = window.marked;
    try {
      window.marked = undefined;
      const node = render("**readable**");
      assert(node.textContent === "**readable**", "plain fallback lost content");
    } finally { window.marked = saved; }
  });
  document.querySelector("#results").textContent = JSON.stringify(results);
  document.title = results.every(r => r.pass) ? "Renderer tests PASS " + results.length : "Renderer tests FAILED";
})();

