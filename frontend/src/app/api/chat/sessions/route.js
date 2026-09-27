import { relayToBackend } from "@/lib/bff";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export async function GET(request) {
  return relayToBackend(request, "/api/chat/sessions", { method: "GET" });
}

export async function POST(request) {
  return relayToBackend(request, "/api/chat-extra/sessions", { method: "POST" });
}
