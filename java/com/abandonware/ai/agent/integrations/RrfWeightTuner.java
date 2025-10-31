
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;



/**
 * Simple grid search tuner for RRF weights on a JSONL dataset.
 * Each JSONL line example should provide fields:
 *  {"query":"/* ... *&#47;", "relevant_ids":["/* ... *&#47;"], "local":[{"id":/* ... *&#47;}], "web":[{"id":/* ... *&#47;}]}
 */
public class RrfWeightTuner {

    public static void main(String[] args) throws Exception {
        Path train = Paths.get(System.getenv().getOrDefault("RRF_TRAIN_FILE", args.length>0?args[0]:"./data/rrf_train.jsonl"));
        List<Example> examples = load(train);
        double[] Ks = parseDoubles(System.getenv().getOrDefault("RRF_TUNE_KS", "20,60,120"));
        double[] WL = parseDoubles(System.getenv().getOrDefault("RRF_TUNE_W_LOCAL", "0.5,1.0,1.5,2.0"));
        double[] WW = parseDoubles(System.getenv().getOrDefault("RRF_TUNE_W_WEB", "0.5,1.0,1.5,2.0"));
        int at = Integer.parseInt(System.getenv().getOrDefault("RRF_TUNE_AT", "10"));

        double bestF1 = -1.0;
        double bestK=60, bestWl=1.0, bestWw=1.0;

        for (double K : Ks) for (double wl : WL) for (double ww : WW) {
            double f1 = eval(examples, K, wl, ww, at);
            if (f1 > bestF1) { bestF1 = f1; bestK=K; bestWl=wl; bestWw=ww; }
        }

        String out = "{\"K\":"+bestK+",\"w_local\":"+bestWl+",\"w_web\":"+bestWw+"}";
        Files.writeString(Paths.get("./rrf_weights.json"), out, StandardCharsets.UTF_8);
        System.out.println("[RrfWeightTuner] best F1@"+at+"="+bestF1+" with K="+bestK+" w_local="+bestWl+" w_web="+bestWw);
    }

    record Example(String query, Set<String> relevant, List<Map<String,Object>> local, List<Map<String,Object>> web) {}

    static List<Example> load(Path jsonl) throws IOException {
        List<Example> out = new ArrayList<>();
        if (!Files.exists(jsonl)) return out;
        for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            line = line.trim(); if (line.isEmpty()) continue;
            Map<String,Object> m = parseFlatJson(line);
            String q = String.valueOf(m.get("query"));
            Set<String> rel = new HashSet<>(List.of(String.valueOf(m.getOrDefault("relevant_ids","")).split(",")));
            List<Map<String,Object>> local = (List<Map<String,Object>>) m.getOrDefault("local", List.of());
            List<Map<String,Object>> web = (List<Map<String,Object>>) m.getOrDefault("web", List.of());
            out.add(new Example(q, rel, local, web));
        }
        return out;
    }

    static double eval(List<Example> ex, double K, double wl, double ww, int at) {
        double tp=0, fp=0, fn=0;
        for (Example e : ex) {
            List<Map<String,Object>> fused = fuse(e.local, e.web, K, wl, ww);
            Set<String> pred = new HashSet<>();
            for (int i=0;i<Math.min(at, fused.size());i++) pred.add(String.valueOf(fused.get(i).get("id")));
            for (String id : pred) {
                if (e.relevant.contains(id)) tp++; else fp++;
            }
            for (String id : e.relevant) {
                if (!pred.contains(id)) fn++;
            }
        }
        double precision = tp / Math.max(1.0, tp + fp);
        double recall = tp / Math.max(1.0, tp + fn);
        return 2 * precision * recall / Math.max(1.0, precision + recall);
    }

    static List<Map<String,Object>> fuse(List<Map<String,Object>> local, List<Map<String,Object>> web, double K, double wl, double ww) {
        Map<String, Map<String,Object>> byKey = new LinkedHashMap<>();
        Map<String, Double> score = new HashMap<>();
        int r=1;
        for (Map<String,Object> m : local) { String key = String.valueOf(m.get("id")); byKey.putIfAbsent(key, m); score.put(key, score.getOrDefault(key,0.0)+ wl/(K+r)); r++; }
        r=1;
        for (Map<String,Object> m : web) { String key = String.valueOf(m.get("id")); byKey.putIfAbsent(key, m); score.put(key, score.getOrDefault(key,0.0)+ ww/(K+r)); r++; }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(score.entrySet());
        entries.sort((a,b)-> Double.compare(b.getValue(), a.getValue()));
        List<Map<String,Object>> out = new ArrayList<>();
        int rank = 1;
        for (var e : entries) {
            Map<String,Object> m = new LinkedHashMap<>(byKey.get(e.getKey()));
            m.put("score", e.getValue()); m.put("rank", rank++);
            out.add(m);
        }
        return out;
    }

    // Tiny JSON parser for flat cases; for full fidelity, use Jackson in real usage.
    static Map<String,Object> parseFlatJson(String s) {
        Map<String,Object> m = new HashMap<>();
        s = s.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length()-1);
        for (String part : s.split(",")) {
            int i = part.indexOf(':'); if (i<0) continue;
            String k = part.substring(0,i).replaceAll("[\"{}\\s]","");
            String v = part.substring(i+1).trim();
            v = v.replaceAll("^[\"]|[\"]$", "");
            m.put(k, v);
        }
        return m;
    }

    static double[] parseDoubles(String s) {
        String[] p = s.split(",");
        double[] out = new double[p.length];
        for (int i=0;i<p.length;i++) out[i] = Double.parseDouble(p[i].trim());
        return out;
    }
}