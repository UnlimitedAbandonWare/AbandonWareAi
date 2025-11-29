# GPT‑PRO‑AGENT Header — v2 (Spec)

Place this Javadoc block immediately before the top-level type (class/interface/enum).
Keep it ≤ 12 lines to avoid binary bloat. Follow with a YAML `agent-hint` block.

```java
/**
 * [GPT-PRO-AGENT v2] — concise navigation header (no runtime effect).
 * Module: <package.ClassName>
 * Role: <controller|service|repository|config|component|class>
 * Key Endpoints: <METHOD /path, ...>        // only for controllers
 * Feature Flags: <flag.key.*, ...>          // if referenced
 * Dependencies: <project.internal.deps, ...>// brief
 * Observability: propagates trace headers if present.
 * Thread-Safety: <appears stateless|uses concurrent primitives|unknown>.
 */
/* agent-hint:
id: <package.ClassName>
role: <role>
api:
  - <METHOD /path>
flags: [<flag1>, <flag2>]
*/
```

Notes:
- Never alter code semantics; comments only.
- If an older `[GPT‑PRO‑AGENT]` header exists, replace it with this compact v2 format.
- On controllers, collect class-level `@RequestMapping` and method-level `@Get|Post|...Mapping`.
- Limit lists to 3–4 items; elide with `+N more`.
