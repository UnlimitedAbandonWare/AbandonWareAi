# Next.js RAG BFF Integration Map

## Verified Local Next Project

- Existing project path: `C:\Users\nninn\OneDrive\Desktop\travel-graphrag-chatbot`
- Current `package.json` observed during skill creation:
  - `next`: `16.2.6`
  - `react`: `19.2.4`
  - `@langchain/*`, Pinecone, Supabase, Neo4j packages are installed
  - scripts: `dev`, `build`, `start`, `lint`
- Local `AGENTS.md` says this Next version has breaking changes and local docs in `node_modules/next/dist/docs/` should be read before coding.

If the user specifically requires Next.js 14, do not downgrade the existing project silently. Either create a separate pinned project or ask for approval to change dependency versions.

## Recommended Architecture

```text
Browser
  |
  | same-origin fetch / EventSource
  v
Next.js App Router UI
  |
  | Route Handler BFF, server-only env
  v
Spring Boot demo-1 RAG server
  |
  | existing Java safety gates
  v
Web / Vector / Memory / Local LLM
```

## Environment Shape

Use server-only variables:

```text
RAG_BACKEND_URL=http://127.0.0.1:8080
RAG_INTERNAL_ADMIN_TOKEN=<server-only if needed>
```

Avoid:

```text
NEXT_PUBLIC_OPENAI_API_KEY
NEXT_PUBLIC_OWNER_TOKEN
NEXT_PUBLIC_RAG_ADMIN_TOKEN
```

## Route Handler Sketch

This is a pattern, not a drop-in file. Confirm active backend endpoint names first.

```ts
export const dynamic = 'force-dynamic';

export async function POST(request: Request) {
  const backend = process.env.RAG_BACKEND_URL ?? 'http://127.0.0.1:8080';
  const sessionId = request.headers.get('x-session-id') ?? crypto.randomUUID();
  const requestId = request.headers.get('x-request-id') ?? crypto.randomUUID();

  const upstream = await fetch(`${backend}/api/chat/stream`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-session-id': sessionId,
      'x-request-id': requestId,
    },
    body: await request.text(),
    cache: 'no-store',
  });

  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      'content-type': upstream.headers.get('content-type') ?? 'text/event-stream',
      'cache-control': 'no-store',
      'x-session-id': sessionId,
      'x-request-id': requestId,
    },
  });
}
```

## Next.js 14 App Router Notes

- Route Handlers live in `app/**/route.ts` and use Web `Request`/`Response` APIs.
- Route Handlers support `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, and `OPTIONS`.
- Server Components can fetch data directly; Client Components should call Route Handlers when server-side secrecy is needed.
- For dynamic streaming routes, set `dynamic = 'force-dynamic'` and use `cache: 'no-store'` for backend chat calls.

## UI Migration Targets From Spring Templates

- Current backend template: `main/resources/templates/chat-ui.html`
- Current backend JS/CSS: `main/resources/static/js/chat.js`, `main/resources/templates/chat-style.css`
- Next UI should preserve:
  - session selector/list
  - streaming transcript
  - model/mode badges
  - citation/evidence panel
  - diagnostics links
  - cancel/retry controls

