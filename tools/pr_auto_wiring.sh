#!/usr/bin/env bash
set -euo pipefail

# pr_auto_wiring.sh
# - Create a feature branch and add AOP/Configuration auto-wiring for telemetry/alias/mpc.
# - No external deps. Safe: Feature flags OFF by default.

ROOT="${1:-.}"
BRANCH="${PR_BRANCH:-feature/autowiring-aop}"

echo "[i] Working dir: $ROOT"
cd "$ROOT"

if git rev-parse --git-dir > /dev/null 2>&1; then
  echo "[i] Git repo detected."
else
  echo "[!] Not a git repo. Initialize? (y/N)"
  read -r yn
  if [[ "${yn:-N}" =~ ^[Yy]$ ]]; then
    git init
  else
    echo "[!] Aborting."
    exit 1
  fi
fi

git checkout -b "$BRANCH" 2>/dev/null || git checkout "$BRANCH"

mkdir -p src/main/java/com/example/lms/config/aop
mkdir -p src/main/resources
mkdir -p docs

# --- Write AutoWiringConfig ---
cat > src/main/java/com/example/lms/config/AutoWiringConfig.java <<'JAVA'
package com.example.lms.config;

import com.example.lms.alias.TileAliasCorrector;
import com.example.lms.mpc.MpcPreprocessor;
import com.example.lms.mpc.NoopMpcPreprocessor;
import com.example.lms.telemetry.MatrixTelemetryExtractor;
import com.example.lms.telemetry.VirtualPointService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AutoWiringConfig
 * - Registers beans for telemetry, alias overlay and MPC preprocessor.
 * - Pure Spring config, no external deps. All beans are behind feature flags.
 *
 * Properties (default false):
 *   features.telemetry.virtual-point.enabled
 *   features.alias.corrector.enabled
 *   features.mpc.enabled
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.context.ApplicationContext")
public class AutoWiringConfig {

    // --- Telemetry beans ---
    @Bean
    @ConditionalOnProperty(name = "features.telemetry.virtual-point.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public MatrixTelemetryExtractor matrixTelemetryExtractor() {
        return new MatrixTelemetryExtractor();
    }

    @Bean
    @ConditionalOnProperty(name = "features.telemetry.virtual-point.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public VirtualPointService virtualPointService() {
        return new VirtualPointService();
    }

    // --- Alias corrector (overlay) ---
    @Bean
    @ConditionalOnProperty(name = "features.alias.corrector.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public TileAliasCorrector tileAliasCorrector() {
        return new TileAliasCorrector();
    }

    // --- MPC preprocessor ---
    @Bean
    @ConditionalOnProperty(name = "features.mpc.enabled", havingValue = "true")
    @ConditionalOnMissingBean(MpcPreprocessor.class)
    public MpcPreprocessor mpcPreprocessor() {
        // default no-op impl; real impl can override via own @Bean
        return new NoopMpcPreprocessor();
    }
}
JAVA

# --- Write RagPipelineHooks (AOP) ---
cat > src/main/java/com/example/lms/config/aop/RagPipelineHooks.java <<'JAVA'
package com.example.lms.config.aop;

import com.example.lms.alias.TileAliasCorrector;
import com.example.lms.telemetry.MatrixTelemetryExtractor;
import com.example.lms.telemetry.VirtualPointService;
import com.example.lms.mpc.MpcPreprocessor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RagPipelineHooks (AOP)
 * - ChatService.chat(..) 단일 진입점에 최소 침습 훅을 제공
 * - 텔레메트리(종료 시), 별칭 교정(입력 전), MPC 전처리(의도 감지 시)
 * - Fail-soft: 어떤 예외도 상류로 전파하지 않음
 */
@Aspect
@Configuration
@ConditionalOnClass(ProceedingJoinPoint.class)
public class RagPipelineHooks {

    @Autowired(required = false) private TileAliasCorrector alias;
    @Autowired(required = false) private MatrixTelemetryExtractor mtx;
    @Autowired(required = false) private VirtualPointService vps;
    @Autowired(required = false) private MpcPreprocessor mpc;

    @Autowired(required = false) private org.springframework.core.env.Environment env;

    private boolean flag(String key) {
        try {
            if (env == null) return false;
            String v = env.getProperty(key, "false");
            return "true".equalsIgnoreCase(v);
        } catch (Exception ignore) { return false; }
    }

    @Around("execution(* com.example.lms.service.ChatService.chat(..))")
    public Object aroundChatService(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        Object[] args = pjp.getArgs();

        // --- 1) Alias overlay (입력 전) ---
        if (flag("features.alias.corrector.enabled") && alias != null) {
            tryAliasPreprocess(args);
        }

        // --- 2) MPC preproc (입력 전: 의도 감지+blob 정규화) ---
        if (flag("features.mpc.enabled") && mpc != null) {
            tryMpcNormalize(args);
        }

        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed(args);
            return result;
        } catch (Throwable e) {
            error = e;
            throw e;
        } finally {
            // --- 3) Telemetry (종료 시 스냅샷→NDJSON) ---
            if (flag("features.telemetry.virtual-point.enabled") && mtx != null && vps != null) {
                try {
                    Map<String,Object> run = captureRunSummary(t0, result, error);
                    Map<String,Object> snap = mtx.extract(run);
                    String reqId = resolveRequestId();
                    File out = new File(resolvePath("/var/log/app/virt/virt_points.ndjson"));
                    vps.appendNdjson(reqId, snap, out);
                } catch (Exception ignore) { /* fail-soft */ }
            }
        }
    }

    // ---- Impl helpers ----

    private void tryAliasPreprocess(Object[] args) {
        try {
            for (int i=0;i<args.length;i++) {
                Object a = args[i];
                if (a == null) continue;
                if (a instanceof String) {
                    String s = (String) a;
                    String after = alias.correct(s);
                    args[i] = after;
                    return;
                }
                // DTO with field "text" or "query" or "message"
                String[] candidates = {"text","query","message","prompt","input"};
                for (String f : candidates) {
                    try {
                        Field fld = a.getClass().getDeclaredField(f);
                        fld.setAccessible(true);
                        Object v = fld.get(a);
                        if (v instanceof String) {
                            String after = alias.correct((String) v);
                            fld.set(a, after);
                            return;
                        }
                    } catch (NoSuchFieldException ignore) {}
                }
            }
        } catch (Exception ignore) {}
    }

    private void tryMpcNormalize(Object[] args) {
        try {
            for (Object a : args) {
                if (a == null) continue;
                // context map heuristic
                if (a instanceof Map) {
                    Map<?,?> m = (Map<?,?>) a;
                    Object blob = firstNonNull(m.get("voxel"), m.get("blob"), m.get("volume"));
                    if (blob != null && likelyVoxelIntent(m)) {
                        Object norm = mpc.normalizeVoxel(blob);
                        if (m instanceof java.util.concurrent.ConcurrentMap) {
                            ((java.util.concurrent.ConcurrentMap)m).put("voxel", norm);
                        } else if (m instanceof java.util.HashMap) {
                            ((HashMap)m).put("voxel", norm);
                        }
                        return;
                    }
                }
                // DTO with "voxel"/"blob" field
                String[] fields = {"voxel","blob","volume"};
                for (String f : fields) {
                    try {
                        Field fld = a.getClass().getDeclaredField(f);
                        fld.setAccessible(true);
                        Object v = fld.get(a);
                        if (v != null && likelyVoxelIntent(a)) {
                            Object norm = mpc.normalizeVoxel(v);
                            fld.set(a, norm);
                            return;
                        }
                    } catch (NoSuchFieldException ignore) {}
                }
            }
        } catch (Exception ignore) {}
    }

    private boolean likelyVoxelIntent(Object o) {
        try {
            String src = null;
            if (o instanceof Map) {
                Object s = ((Map<?,?>)o).get("text");
                if (s == null) s = ((Map<?,?>)o).get("query");
                if (s instanceof String) src = (String) s;
            } else {
                // scan common fields
                for (String f : new String[]{"text","query","message","prompt"}) {
                    try {
                        Field fld = o.getClass().getDeclaredField(f);
                        fld.setAccessible(true);
                        Object v = fld.get(o);
                        if (v instanceof String) { src = (String) v; break; }
                    } catch (NoSuchFieldException ignore) {}
                }
            }
            if (src == null) return false;
            String t = src.toLowerCase(Locale.ROOT);
            return t.contains("voxel") || t.contains("홀로그") || t.contains("optics") || t.contains("3d ");
        } catch (Exception ignore) { return false; }
    }

    private Object firstNonNull(Object... xs) {
        for (Object x : xs) if (x != null) return x;
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> captureRunSummary(long t0, Object result, Throwable error) {
        Map<String,Object> m = new HashMap<>();
        try {
            long ms = Math.max(0L, (System.nanoTime() - t0)/1_000_000L);
            m.put("latency.ms", (double) ms);

            // Try TraceContext (if exists) to enrich
            try {
                Class<?> tc = Class.forName("com.example.lms.trace.TraceContext");
                Method cur = tc.getDeclaredMethod("current");
                Object ctx = cur.invoke(null);
                if (ctx != null) {
                    // best-effort getters
                    putIfPresent(m, "source.web.count", getNumber(ctx, "getWebCount"));
                    putIfPresent(m, "source.vector.count", getNumber(ctx, "getVectorCount"));
                    putIfPresent(m, "source.kg.count", getNumber(ctx, "getKgCount"));
                    putIfPresent(m, "authority.avg", getNumber(ctx, "getAuthorityScore"));
                    putIfPresent(m, "novelty.avg", getNumber(ctx, "getNoveltyScore"));
                    putIfPresent(m, "contradiction.score", getNumber(ctx, "getContradictionScore"));
                    putIfPresent(m, "reranker.cost", getNumber(ctx, "getRerankCostTokens"));
                    putIfPresent(m, "risk.score", getNumber(ctx, "getRiskScore"));
                    putIfPresent(m, "budget.usage", getNumber(ctx, "getBudgetUsage"));
                }
            } catch (Throwable ignore) {}

            // result-based enrichment (if common methods exist)
            if (result != null) {
                putIfPresent(m, "authority.avg", getNumber(result, "getAuthorityScore"));
                putIfPresent(m, "risk.score", getNumber(result, "getRiskScore"));
            }
        } catch (Exception ignore) {}
        return m;
    }

    private void putIfPresent(Map<String,Object> m, String key, Number n) {
        if (n != null) m.put(key, n.doubleValue());
    }

    private Number getNumber(Object o, String method) {
        try {
            Method m = o.getClass().getMethod(method);
            Object v = m.invoke(o);
            if (v instanceof Number) return (Number) v;
            return null;
        } catch (Exception e) { return null; }
    }

    private String resolveRequestId() {
        try {
            Class<?> f = Class.forName("com.example.lms.trace.TraceContext");
            Method cur = f.getDeclaredMethod("current");
            Object ctx = cur.invoke(null);
            if (ctx != null) {
                try {
                    Method m = ctx.getClass().getMethod("getRequestId");
                    Object v = m.invoke(ctx);
                    if (v != null) return String.valueOf(v);
                } catch (Exception ignore) {}
            }
        } catch (Throwable ignore) {}
        return "";
    }

    private String resolvePath(String def) {
        try {
            if (env == null) return def;
            String v = env.getProperty("telemetry.virtual-point.path");
            return (v == null || v.isBlank()) ? def : v;
        } catch (Exception ignore) { return def; }
    }
}
JAVA

# --- Ensure feature flags example exists ---
if [[ ! -f src/main/resources/application-features-example.yml ]]; then
  cat > src/main/resources/application-features-example.yml <<'YML'
features:
  telemetry:
    virtual-point:
      enabled: false
  alias:
    corrector:
      enabled: false
  mpc:
    enabled: false
YML
fi

# --- Doc ---
cat > docs/PR4_autowiring_aop.md <<'MD'
# PR 4 — Auto Wiring (AOP/Configuration, 기본 OFF)

**What**
- `AutoWiringConfig`: Telemetry/Alias/MPC 빈 등록 (플래그 게이트)
- `RagPipelineHooks`(AOP): `ChatService.chat(..)` 진입점에서
  - 입력 전 별칭 교정(Overlay)
  - 입력 전 MPC 전처리(No-op 기본)
  - 종료 시 Telemetry NDJSON(9‑Matrix → VirtualPoint)

**Flags (application.yml)**
```yaml
features:
  telemetry:
    virtual-point:
      enabled: false
  alias:
    corrector:
      enabled: false
  mpc:
    enabled: false

telemetry:
  virtual-point:
    path: /var/log/app/virt/virt_points.ndjson   # (옵션) 파일 경로
```
**Rollout**
- DEV: Telemetry만 ON → NDJSON 기록 확인
- STAGE: Alias 10% ON (A/B), MPC는 계속 OFF
- PROD: 단계적 확대

**Risk**
- AOP 비활성 환경에서는 훅 무동작(안전)
- 모든 로직 Fail‑soft (상류 예외 전파 없음)
MD

git add -A
git commit -m "feat(autowiring): add Spring AOP hooks + auto configuration (flagged, fail-soft)"

echo "[✓] PR branch '$BRANCH' ready. Push with: git push -u origin $BRANCH"
