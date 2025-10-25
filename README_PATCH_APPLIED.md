# Patch: Guest Session Rooms + History + Admin Visibility (src_53)

This patch introduces session-based (guest) identity, Redis-backed rooms and message history,
login-time migration to user accounts, and an admin listing endpoint.

## Highlights
- gid cookie is issued automatically for non-logged-in visitors. It establishes a stable session.
- /api/rooms — create & list rooms bound to current identity (guest or user).
- /api/messages — post messages; /api/rooms/{roomId}/messages — read history.
- /api/identity/claim — migrate all guest data (current session) to a user identity.
  Provide a user id via header `X-User-Id` or JSON body `{ "userId": "..." }`.
- /api/admin/rooms?type=all|guest|user|migrated — admin visibility over all rooms.

## Implementation Notes
- Added `IdentityInterceptor` that sets `gid` cookie and initializes `ContextBridge` with the session id when absent.
- Introduced simple domain classes: `Room`, `Message` and a Redis-backed `RoomService`.
- No DB required beyond Spring Data Redis already present in the project.
- The migration rewrites room ownership and re-writes message authors from `guest:<session>` to `user:<userId>`.

## Security
- The admin endpoint is not authenticated by this patch (no Spring Security in project). Protect it at proxy level or add Spring Security if needed.

## Files Added
- `app/src/main/java/com/abandonware/ai/agent/identity/IdentityInterceptor.java`
- `app/src/main/java/com/abandonware/ai/agent/identity/IdentityUtils.java`
- `app/src/main/java/com/abandonware/ai/agent/room/Room.java`
- `app/src/main/java/com/abandonware/ai/agent/room/Message.java`
- `app/src/main/java/com/abandonware/ai/agent/room/RoomService.java`
- `app/src/main/java/com/abandonware/ai/agent/web/RoomController.java`
- `app/src/main/java/com/abandonware/ai/agent/web/MessageController.java`
- `app/src/main/java/com/abandonware/ai/agent/web/IdentityController.java`
- `app/src/main/java/com/abandonware/ai/agent/web/AdminController.java`
- Modified: `app/src/main/java/com/abandonware/ai/agent/config/WebConfig.java` (registered new interceptor).

## How to Use
1. Start the app with Redis reachable (`spring.data.redis.*` already configured).
2. From a browser or client without any auth:
   - `POST /api/rooms` with body `{ "title": "My First Room" }`
   - `POST /api/messages` with body `{ "roomId": "<id>", "content": "hello" }`
   - `GET  /api/rooms/<id>/messages`
3. Later, when the user signs in, call:
   - `POST /api/identity/claim` with header `X-User-Id: <uid>`
     (migrates this session's guest data to the user).
4. Admin can list:
   - `GET /api/admin/rooms?type=all`

## Caveats
- If another upstream already sets `X-Session-Id`, it takes precedence through ConsentInterceptor.
- For production, add proper authz around admin endpoints.
