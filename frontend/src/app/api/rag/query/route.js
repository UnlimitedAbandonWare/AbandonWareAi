import { relayToBackend } from "@/lib/bff";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export async function POST(request) {
  return relayToBackend(request, "/api/rag/query");
}
