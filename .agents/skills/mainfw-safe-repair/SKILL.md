---
name: mainfw-safe-repair
description: Use when Codex analyzes or patches the mainfw Java/Spring overlay against staps.txt
---

# mainfw Safe Repair

## Overview

Treat `mainfw` as a source overlay until a real build root is proven. Repair **runtime contracts**, not portfolio narratives. Every patch starts with a failing test or a reproducible structural probe and ends with fresh build/test evidence.

## Bundled resources

- Run `scripts/probe_mainfw.py` from this skill directory when the repository does not already provide an equivalent structural probe.
- Use `tests/pressure-scenarios.md` to forward-test evidence precedence, dirty-worktree safety, and RuleBreak authorization choices.

## Hard Rules

1. Find the target `pom.xml`/Gradle root and canonical main class before editing.
2. Never create a new build file inside the overlay to manufacture a green build.
3. Never enable `spring.main.allow-bean-definition-overriding=true` to hide collisions.
4. Never register a RuleBreak/ZeroBreak path that treats any nonblank token as authorized.
5. Never print or rewrite secret values, `apikey.ps1`, or existing environment-variable names.
6. Plan YAML is `hint-overlay-v1` unless a tested executor proves otherwise. `plan.when`, `plan.pipeline`, `chain`, and fusion declarations are metadata when no consumer exists.
7. One root cause per commit. No broad package consolidation in a bug-fix commit.
8. Use `Observation → Patch Blocks → Setup Commands → Verification` for every `doXX` block.
9. Report every finding as `positive`, `negative`, or `neutral/evidence_needed`.
10. No completion claim without a fresh full test/build command and exit code.

## Workflow

### 1. Establish evidence

Select the probe root that directly contains both `java/` and `resources/`: use `main` for demo-1's custom active layout, `src/main` for a standard repository, or `.` for an extracted overlay. Then run:

```bash
python .agents/skills/mainfw-safe-repair/scripts/probe_mainfw.py --root <overlay-root> --output build/mainfw-probe-before.json
```

Then identify:

- canonical entrypoint and scan packages;
- default bean-name collisions;
- `AutoConfiguration.imports` coverage;
- MVC interceptor registration;
- Plan keys with no runtime consumer;
- `.yml`/`.properties` critical overlaps;
- build and test availability.

### 2. Rank the break

| Rank | Predicate | Action |
|---|---|---|
| P0 | prevents context startup, leaves intended feature unregistered, or silently defeats authorization/plan routing | fix first with regression test |
| P1 | configuration drift, policy ambiguity, or security contract fragmentation | fix after P0 |
| P2 | duplicate lineage, large config class, source hygiene | separate cleanup commit |
| evidence_needed | build/deployment/runtime fact is absent | document the exact command or file that resolves it |

### 3. Form one hypothesis

Write the hypothesis before code:

```text
I think <specific contract> is broken because <specific registration/data-flow evidence>.
The smallest test is <test name/command>.
```

Do not stack a second fix when the first hypothesis fails. Re-probe and form a new hypothesis.

### 4. Patch minimally

Preferred repair order:

1. canonical application boundary;
2. missing auto-configuration imports;
3. canonical RuleBreak interceptor registration and ThreadLocal cleanup;
4. Plan hint contract and unwired-key validation;
5. critical configuration single-source cleanup;
6. runtime-vs-dataset gate policy split;
7. owner-token verifier adapters;
8. source hygiene.

### 5. Verify fresh

Use the actual repository tool:

```bash
./gradlew clean test && ./gradlew bootJar
# or
./mvnw clean test && ./mvnw package -DskipTests=false
```

Re-run the probe and compare JSON:

```bash
python .agents/skills/mainfw-safe-repair/scripts/probe_mainfw.py --root <overlay-root> --output build/mainfw-probe-after.json
```

Required final checks:

```text
canonical context collision = 0
intended auto-config missing = 0
canonical RuleBreak registered = true
auto-selected plan unwired keys = 0
critical config overlap = 0
raw token/key log = 0
package/path mismatch = 0 or documented legacy exclusion
full tests/build exit = 0
```

## Output Contract

```markdown
## 요약
## 핵심 답변
### doXX
#### Observation
#### Patch Blocks
#### Setup Commands
#### Verification
## 긍정/부정/중립 변화
## 근거/로그/파일경로
## evidence_needed
## 다음 단계(선택)
```

Every changed file must include an exact path. Every behavior claim must include a test name or command output. Redact paths and secrets in logs where needed.

## Common Mistakes

| Mistake | Correct response |
|---|---|
| “Two implementations conflict, so turn on bean overriding.” | Isolate the application boundary or name the exact colliding beans. |
| “The YAML says pipeline, so it executes.” | Find the consumer. If `planDsl.status=not_used`, treat it as metadata. |
| “The auto-config class exists, so Spring loads it.” | Check `AutoConfiguration.imports` or explicit import. |
| “RuleBreak has a validator class, so the header works.” | Confirm the interceptor/filter is registered in the actual servlet/reactive stack. |
| “Set every gate to 0.90/3.” | Separate interactive availability from dataset acceptance and test both. |
| “The dimensions are 4096 because staps says so.” | Read current model/config and benchmark the actual raw/target dimensions. |
| “No tests exist, so patch and test later.” | First add the smallest failing context/contract test in the real build root. |

## Red Flags — Stop

- changing more than one subsystem before a failing test;
- adding a new secret or header name;
- logging raw request tokens, API keys, URLs with query strings, or user prompts;
- moving all duplicate packages in one refactor;
- claiming a feature is active from class existence alone;
- claiming speed/accuracy gains without a benchmark artifact;
- saying “should pass” without running the full command.

All red flags mean: stop, return to evidence collection, and reduce the patch.
