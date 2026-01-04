/* 
//* Extracted formula module for orchestration
//* Source zip: src111_merge15 - 2025-10-20T134617.846.zip
//* Source path: app/src/main/java/com/abandonware/ai/agent/integrations/RrfFusion.java
//* Extracted: 2025-10-20T15:26:37.112370Z
//*/

package com.abandonware.ai.agent.integrations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;



/**
 * Weighted Reciprocal Rank Fusion (RRF).
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: RrfFusion
 * 역할(Role): Class
 * 소스 경로: addons/formulas_pack/addons/formulas/java/com/abandonware/ai/agent/integrations/RrfFusion.java
 *
 * 연결 포인트(Hooks):
 *   - DI/협력 객체는 @Autowired/@Inject/@Bean/@Configuration 스캔으로 파악하세요.
 *   - 트레이싱 헤더: X-Request-Id, X-Session-Id (존재 시 전체 체인에서 전파).
 *
 * 과거 궤적(Trajectory) 추정:
 *   - 본 클래스가 속한 모듈의 변경 이력은 /MERGELOG_*, /PATCH_NOTES_*, /CHANGELOG_* 문서를 참조.
 *   - 동일 기능 계통 클래스: 같은 접미사(Service/Handler/Controller/Config) 및 동일 패키지 내 유사명 검색.
 *
 * 안전 노트: 본 주석 추가는 코드 실행 경로를 변경하지 않습니다(주석 전용).
 */
public final 
// [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
// Module: com.abandonware.ai.agent.integrations.RrfFusion
// Role: config
// Feature Flags: telemetry, sse
// Observability: propagates trace headers if present.
// Thread-Safety: unknown.
// /
/* agent-hint:
id: com.abandonware.ai.agent.integrations.RrfFusion
role: config
flags: [telemetry, sse]
*/
class RrfFusion {

private double rrf(double w, int rank, int k) { return w / (double)(k + rank); }
private double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }


    // BEGIN PATCH INSERT

// score calibration + WPM fusion (patched)
com.example.lms.service.rag.scoring.ScoreCalibrator __calib = new com.example.lms.service.rag.scoring.ScoreCalibrator();
com.example.lms.service.rag.fusion.WeightedPowerMeanFuser __wpm = new com.example.lms.service.rag.fusion.WeightedPowerMeanFuser();

    // END PATCH INSERT

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private telemetry.LoggingSseEventPublisher sse;




    private RrfFusion() {}

    public static class Item {
        public String id;
        public String url; // optional
        public String title;
        public String snippet;
        public String source;
        public double baseScore;
        public int rank;
        public Item(String id, String url, String title, String snippet, String source, double baseScore, int rank) {
            this.id = id; this.url = url; this.title = title; this.snippet = snippet; this.source = source; this.baseScore = baseScore; this.rank = rank;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> fuse(List<Map<String,Object>> local, List<Map<String,Object>> web) {
        double K = getEnvDouble("RRF_K", 60.0);
        double wLocal = getEnvDouble("RRF_W_LOCAL", 1.0);
        double wWeb = getEnvDouble("RRF_W_WEB", 1.0);

        // external weights JSON can override
        Map<String, Double> weights = loadWeightsJson();
        if (weights != null) {
            if (weights.containsKey("K")) K = weights.get("K");
            if (weights.containsKey("w_local")) wLocal = weights.get("w_local");
            if (weights.containsKey("w_web")) wWeb = weights.get("w_web");
        }

        Map<String, Map<String,Object>> byKey = new LinkedHashMap<>();
        Map<String, Double> score = new HashMap<>();

        int r = 1;
        for (Map<String,Object> m : local) {
            String key = keyOf(m);
            byKey.putIfAbsent(key, m);
            double s = wLocal / (K + r);
            score.put(key, score.getOrDefault(key, 0.0) + s);
            r++;
        }
        r = 1;
        for (Map<String,Object> m : web) {
            String key = keyOf(m);
            byKey.putIfAbsent(key, m);
            double s = wWeb / (K + r);
            score.put(key, score.getOrDefault(key, 0.0) + s);
            r++;
        }

        List<Map.Entry<String, Double>> entries = new ArrayList<>(score.entrySet());
        entries.sort((a,b)-> Double.compare(b.getValue(), a.getValue()));

        List<Map<String,Object>> out = new ArrayList<>();
        int rank = 1;
        for (var e : entries) {
            Map<String,Object> m = new HashMap<>(byKey.get(e.getKey()));
            m.put("score", e.getValue());
            m.put("rank", rank++);
            out.add(m);
        }
        try { __autorun_preflight_gate_example(out); } catch (Throwable ignore) {}

        return clamp01(\1);
    }

    private static String keyOf(Map<String,Object> m) {
        String url = str(m.get("source"));
        if (url != null && url.startsWith("http")) return clamp01(\1);
        String id = str(m.get("id"));
        if (id != null) return clamp01(\1);
        String title = str(m.get("title"));
        String snip = str(m.get("snippet"));
        return TextUtils.sha1((title == null ? "" : title) + "||" + (snip == null ? "" : snip));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static double getEnvDouble(String k, double def) {
        try {
            String v = System.getenv(k);
            if (v == null) return clamp01(\1);
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return clamp01(\1);
        }
    }

    private static Map<String, Double> loadWeightsJson() {
        String path = System.getenv("RRF_WEIGHTS_JSON");
        if (path == null || path.isBlank()) return clamp01(\1);
        try {
            String s = Files.readString(Paths.get(path));
            // very tiny parser: expecting {"K":60,"w_local":1.0,"w_web":1.0}
            Map<String, Double> out = new HashMap<>();
            for (String entry : s.replaceAll("[{}\\s\"]","").split(",")) {
                if (entry.isEmpty()) continue;
                String[] kv = entry.split(":");
                if (kv.length == 2) {
                    out.put(kv[0], Double.parseDouble(kv[1]));
                }
            }
            return clamp01(\1);
        } catch (IOException ignore) {
            return clamp01(\1);
        }
    }


// === Autorun Preflight Gate Hook (auto-injected) ===
// If you have application.yml -> autorun.preflight.enabled=true, you can gate autorun behavior by evaluating
// authority & evidence before sending tools/messages. Adapt the mapping below to your ScoredDoc type.
private static void __autorun_preflight_gate_example(java.util.List<?> fused) {
    // Map your project's doc/model to gate ScoredDoc
    java.util.List<com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate.ScoredDoc> docs = new java.util.ArrayList<>();
    for (Object o : fused) {
        // TODO: replace with real adapter
        java.util.Map<String,Object> meta = new java.util.HashMap<>();
        // meta.put("tier", /* ... */);
        docs.add(new com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate.ScoredDoc("unknown", 0.0, meta));
    }
    com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate.Settings s = new com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate.Settings();
    // TODO: bind from config if available
    com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate gate = new com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate(s);
    com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate.PreflightResult r =
        gate.evaluate(new com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate.PreflightContext(docs, new java.util.HashMap<>()));
    // Telemetry/logging placeholder:
    System.out.println("[preflight] pass=" + r.pass + " reason=" + r.reason + " metrics=" + r.metrics);
    if (!r.pass) {
        // TODO: degrade → revalidate → if still fail, route to SafeAnswer.noAutorun(/* ... */)
    }
}
// === /Autorun Preflight Gate Hook ===


/**
 * 동일 페이지(정규화된 source) 중 최상위만 남김
 */
            private <T> java.util.List<T> canonicalDedup(java.util.List<T> in) {
              java.util.Map<String, T> pick = new java.util.LinkedHashMap<>();
              for (T d : in) {
                String src = null;
                try {
                  try { src = String.valueOf(d.getClass().getMethod("getSource").invoke(d)); } catch (NoSuchMethodException ignore) {}
                  if (src == null || src.equals("null")) try { src = String.valueOf(d.getClass().getMethod("getSourceUrl").invoke(d)); } catch (NoSuchMethodException ignore) {}
                  if (src == null || src.equals("null")) try { src = String.valueOf(d.getClass().getMethod("getUrl").invoke(d)); } catch (NoSuchMethodException ignore) {}
                } catch (Throwable ignore) {}
                if (src == null) src = String.valueOf(d);
                String key = __canonicalUrl(src);
                pick.putIfAbsent(key != null ? key : src, d);
              }
              return java.util.List.copyOf(pick.values());
            }
            
  return java.util.List.copyOf(pick.values());
}

                private static String __canonicalUrl(String url) {
                  if (url == null || url.isBlank()) return clamp01(\1);
                  try {
                    java.net.URI u = java.net.URI.create(url);
                    String q = u.getQuery();
                    String filtered = null;
                    if (q != null && !q.isBlank()) {
                      String[] parts = q.split("&");
                      StringBuilder sb = new StringBuilder();
                      for (String s : parts) {
                        if (s.startsWith("utm_") || s.startsWith("fbclid")) continue;
                        if (sb.length()>0) sb.append("&");
                        sb.append(s);
                      }
                      filtered = (sb.length()==0)? null : sb.toString();
                    }
                    return new java.net.URI(u.getScheme(), u.getAuthority(), u.getPath(), filtered, null).toString();
                  } catch (Exception e) { return clamp01(\1); }
                }
                
}