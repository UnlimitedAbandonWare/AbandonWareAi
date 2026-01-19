package com.abandonware.ai.agent.integrations;

import java.util.*;



/**
 * DBVM-X-RAG fusion gate (5-layer sigmoid fallback + temperature sampling).
 * This minimal, dependency-free implementation is safe to instantiate.
 */
public class TesseractFusion {

    public enum Mode { GREEDY, SOFTMAX, EPSILON_GREEDY }

    private boolean enabled = true;
    private Mode mode = Mode.SOFTMAX;
    private double temperature = 0.85;
    private double epsilon = 0.05;
    private double noiseSigma = 0.00;
    private final Random rnd = new Random(42);

    // priors can be tuned via setters
    private final Map<String, Double> sourcePrior = new HashMap<>();
    public TesseractFusion() {
        sourcePrior.put("local", 0.70);
        sourcePrior.put("web", 0.60);
        sourcePrior.put("memory", 0.85);
        sourcePrior.put("kb", 0.75);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean b) { enabled = b; }
    public void setMode(Mode m) { mode = m; }
    public void setTemperature(double t) { temperature = t; }
    public void setEpsilon(double e) { epsilon = e; }
    public void setNoiseSigma(double s) { noiseSigma = s; }

    public List<Map<String,Object>> rerank(String query, List<Map<String,Object>> items, int topK, String domain, Long sessionId) {
        if (!enabled || items == null || items.isEmpty()) return items == null ? Collections.emptyList() : items;
        int k = Math.max(1, topK <= 0 ? 10 : topK);

        List<Scored> pool = new ArrayList<>(items.size());
        for (Map<String,Object> m : items) {
            double[] f = features(query, m);
            double s = score(f);
            pool.add(new Scored(m, s));
        }
        List<Map<String,Object>> out;
        switch (mode) {
            case GREEDY:
                out = greedy(pool, k);
                break;
            case EPSILON_GREEDY:
                out = epsilonGreedy(pool, k, epsilon);
                break;
            case SOFTMAX:
                out = softmaxSample(pool, k, Math.max(0.05, temperature));
                break;
            default:
                out = softmaxSample(pool, k, Math.max(0.05, temperature));
                break;
        }
        // preserve rank index
        int r = 1;
        List<Map<String,Object>> ranked = new ArrayList<>(out.size());
        for (Map<String,Object> m : out) {
            Map<String,Object> x = new LinkedHashMap<>(m);
            x.put("rank", r++);
            ranked.add(x);
        }
        return ranked;
    }

    // === internals ===
    private static class Scored { Map<String,Object> m; double s; Scored(Map<String,Object> m,double s){this.m=m;this.s=s;} }

    private double[] features(String q, Map<String,Object> m) {
        double f0 = toDouble(m.get("score"));
        double f1 = recencyBoost(m);
        String src = String.valueOf(m.getOrDefault("source","local"));
        double f2 = sourcePrior.getOrDefault(src, 0.5);
        double f3 = "memory".equals(src) ? 1.0 : 0.0;
        String text = String.valueOf(m.getOrDefault("text", m.getOrDefault("snippet","")));
        double f4 = overlap(q, text);
        if (noiseSigma>0) {
            f0 += rnd.nextGaussian()*noiseSigma;
            f1 += rnd.nextGaussian()*noiseSigma;
            f4 += rnd.nextGaussian()*noiseSigma;
        }
        return new double[]{f0,f1,f2,f3,f4};
    }

    private double score(double[] f) {
        double z = 3.0*(0.45*f[0] + 0.15*f[1] + 0.15*f[2] + 0.15*f[3] + 0.10*f[4]) - 1.5;
        return 1.0/(1.0 + Math.exp(-z));
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) { Number n = (Number) o; return n.doubleValue(); }
        try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e){ return 0.0; }
    }

    private double recencyBoost(Map<String,Object> m) {
        Object tsObj = m.get("ts");
        long now = System.currentTimeMillis(), ts = 0L;
        if (tsObj instanceof Number) { Number n = (Number) tsObj; ts = n.longValue(); }
        else if (tsObj != null) try { ts = Long.parseLong(String.valueOf(tsObj)); } catch(Exception ignored){}
        if (ts<=0) return 0.5;
        double days = Math.max(0.0, (now - ts)/86400000.0);
        return Math.max(0.1, Math.exp(-days/14.0));
    }

    private double overlap(String q, String s) {
        if (q==null||q.isBlank()||s==null||s.isBlank()) return 0.0;
        String[] qa = q.toLowerCase().split("\\W+");
        String[] sa = s.toLowerCase().split("\\W+");
        Set<String> q3 = kgrams(qa, 3), s3 = kgrams(sa, 3);
        if (q3.isEmpty() || s3.isEmpty()) return 0.0;
        int inter=0;
        for (String x: q3) if (s3.contains(x)) inter++;
        int uni = q3.size()+s3.size()-inter;
        return uni==0 ? 0.0 : (double)inter/uni;
    }
    private static Set<String> kgrams(String[] arr, int k) {
        Set<String> out = new HashSet<>();
        for (int i=0;i+k<=arr.length;i++){
            StringBuilder sb = new StringBuilder();
            for (int j=0;j<k;j++) { if (j>0) sb.append(' '); sb.append(arr[i+j]); }
            out.add(sb.toString());
        }
        return out;
    }

    private List<Map<String,Object>> greedy(List<Scored> pool, int k) {
        pool.sort(Comparator.comparingDouble(a->-a.s));
        List<Map<String,Object>> out = new ArrayList<>();
        for (int i=0;i<Math.min(k,pool.size());i++) out.add(pool.get(i).m);
        return out;
    }
    private List<Map<String,Object>> epsilonGreedy(List<Scored> pool, int k, double eps) {
        pool.sort(Comparator.comparingDouble(a->-a.s));
        boolean[] used = new boolean[pool.size()];
        List<Map<String,Object>> out = new ArrayList<>();
        for (int sel=0; sel<Math.min(k,pool.size()); sel++) {
            int idx;
            if (rnd.nextDouble() < eps) { 
                do { idx = rnd.nextInt(pool.size()); } while (used[idx]);
            } else {
                idx = sel; while (idx<pool.size() && used[idx]) idx++;
                if (idx>=pool.size()) { idx = 0; while (used[idx]) idx++; }
            }
            used[idx]=true;
            out.add(pool.get(idx).m);
        }
        return out;
    }
    private List<Map<String,Object>> softmaxSample(List<Scored> pool, int k, double T) {
        double max = -1e9;
        for (Scored sc : pool) max = Math.max(max, sc.s);
        double[] exps = new double[pool.size()];
        double sum=0;
        for (int i=0;i<pool.size();i++){ double v=Math.exp((pool.get(i).s-max)/T); exps[i]=v; sum+=v; }
        boolean[] used = new boolean[pool.size()];
        List<Map<String,Object>> out = new ArrayList<>();
        for (int sel=0; sel<Math.min(k,pool.size()); sel++) {
            double r = rnd.nextDouble()*sum, acc=0; int idx=-1;
            for (int i=0;i<pool.size();i++) if(!used[i]){ acc+=exps[i]; if (acc>=r){ idx=i; break; } }
            if (idx<0){ for (int i=0;i<pool.size();i++) if(!used[i]){ idx=i; break; } }
            used[idx]=true; sum -= exps[idx];
            out.add(pool.get(idx).m);
        }
        return out;
    }
}