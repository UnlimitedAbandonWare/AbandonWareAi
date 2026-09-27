import { relayToBackend } from "@/lib/bff";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export async function GET(request, context) {
  const { id } = await context.params;
  return relayToBackend(request, `/api/chat/sessions/${encodeURIComponent(id)}`, { method: "GET" });
}

export async function DELETE(request, context) {
  const { id } = await context.params;
  return relayToBackend(request, `/api/chat/sessions/${encodeURIComponent(id)}`, { method: "DELETE" });
}
