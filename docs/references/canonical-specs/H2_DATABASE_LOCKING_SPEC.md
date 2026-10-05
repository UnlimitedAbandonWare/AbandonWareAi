---
title: "H2 Database Locking and AUTO_SERVER Concurrency Specification"
category: "canonical-spec"
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 365
expiresAt: "2027-10-05"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation + live_repo_contract"
reviewAfterDays: 365
cadence: "evergreen"
stability: "high"
decayRate: "low"
stabilityReason: "H2 2.x 파일 잠금·자동 혼합 모드는 엔진 설계 계약으로 버전 내 불변"
runtimeEnforcement: "unchanged"
canonicalSources:
  - "https://www.h2database.com/html/features.html#auto_mixed_mode"
  - "https://www.h2database.com/html/advanced.html#file_locking_protocols"
---

# H2 Database 2.x Locking & Concurrency — canonical spec

## 1. File-lock protocols (`FILE_LOCK` URL option, H2 2.x)

| Value | Mechanism | Notes |
|---|---|---|
| `FILE` (default) | Lock watchdog thread: the opening process creates `<db>.lock.db` and periodically rewrites it; a second process sees the live lock file and is refused. | Standard embedded single-writer guard. |
| `SOCKET` | The first process binds a TCP server socket on a port recorded in `.lock.db`; later readers detect a live socket. | Suited to `AUTO_SERVER`; lock survives file-share quirks better than the file watchdog. |
| `FS` | Native OS file lock via `FileChannel` lock. | Strongest single-host guarantee; behaves differently on NFS/SMB shares. |
| `NO` | No file locking at all. | Unsafe for concurrent writers — do not use for lmsdb. |
| `READ` | Read-only media (CD/zip). | Not applicable to writable stores. |

`.lock.db` exists only while a process holds the DB (or after an unclean
shutdown) — its presence alone does not prove corruption.

## 2. `AUTO_SERVER=TRUE` — Automatic Mixed Mode

- The **first** process to open `jdbc:h2:file:<path>` runs embedded **and**
  silently starts a TCP server on an ephemeral port, writing the port into
  `.lock.db`.
- **Subsequent** processes opening the same file URL read `.lock.db`, see a
  live server, and connect as TCP clients automatically — mixed
  embedded/server concurrency with no dedicated daemon.
- Caveats: all sharers must see the same filesystem path (same lock file);
  `AUTO_SERVER_TIMEOUT` bounds the startup race; the first process exiting
  drops the shared server (later clients must re-open).

## 3. demo-1 lmsdb contract (verified 2026-10-05)

- Live URL shape (`scripts/db_agent.py:5`): `jdbc:h2:file:./var/meta-display-db/lmsdb;MODE=MariaDB;DATABASE_TO_UPPER=false` — **no `AUTO_SERVER`**: the file DB is single-owner embedded while the Start-RAG JVM runs.
- `MODE=MariaDB` is H2 compatibility mode, **not** a MariaDB server.
- `python -B scripts/db_agent.py` exits: `0` ok · `2` usage · `3` **`locked`** · `4` not-found · `5` error (PS wrapper `scripts/db-agent.ps1` mirrors).
- **exit 3 `locked`** = the running JVM holds `lmsdb.mv.db` — *an answer, not a failure*. Never kill the server or delete the DB file over it. Reads go through `--via auto` live fallback; writes need `--dry-run` then `--i-mean-it` + `--allow-tables` or explicit user approval (`docs/agents-rules/DEMO1-DB-AGENT-SSOT.md`).
- Shared read-only context without touching the live file: `scripts/meta_display_db_export.py` → `var/meta-display-db/` export (`demo1-meta-display-db-export`).

## 4. Stale `.lock.db` recovery procedure

1. Prove no process owns the DB: `db_agent.py status`, plus check the Start-RAG
   JVM / java processes are actually down. A live owner = stop, do not touch.
2. Only when ownership is proven absent may a leftover `lmsdb.lock.db` be
   deleted — it is a lock artifact, not data. Never delete `lmsdb.mv.db`.
3. Reopen via the normal entry (`db_agent.py` / Start-RAG); H2 runs its own
   recovery on the store file if needed.
4. If `exit 3` persists with **no** java process, suspect an OS-level stale
   handle or a second checkout sharing the path — investigate before deleting.
