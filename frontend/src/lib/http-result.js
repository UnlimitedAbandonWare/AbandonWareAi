const MAX_TEXT_CHARS = 1200;

export async function readJsonOrText(response) {
  const contentType = response.headers.get("content-type") || "";
  const text = await response.text();
  if (contentType.toLowerCase().includes("application/json")) {
    if (!text) return { kind: "json", contentType, value: null };
    try {
      return { kind: "json", contentType, value: JSON.parse(text) };
    } catch {
      return { kind: "text", contentType, text: text.slice(0, MAX_TEXT_CHARS) };
    }
  }
  return { kind: "text", contentType, text: text.slice(0, MAX_TEXT_CHARS) };
}
