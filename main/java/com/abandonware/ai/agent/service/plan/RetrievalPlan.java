package com.abandonware.ai.agent.service.plan;

import java.util.Map;

public class RetrievalPlan {

    private String id;
    private String desc;
    private Map<String, Integer> k;
    private Calibration calibration = new Calibration();
    private Rrf rrf = new Rrf();
    private Guard guard = new Guard();
    private Map<String, Object> override;

    public static class Calibration {
        public Recency recency = new Recency();
        public Authority authority = new Authority();
        public Scale scale = new Scale();
        public static class Recency { public int halfLifeDays = 21; public double maxBoost = 0.25; }
        public static class Authority { public String profile = ""; public double maxBoost = 0.20; }
        public static class Scale { public String method = "isotonic"; }
    }
    public static class Rrf {
        public int k = 60;
        public Map<String, Double> weight;
    }
    public static class Guard {
        public Onnx onnx = new Onnx();
        public static class Onnx { public int maxConcurrent = 4; public int budgetMs = 800; }
    }

    public String id() { return id; }
    public String desc() { return desc; }
    public Map<String,Integer> k() { return k == null ? java.util.Map.of() : k; }
    public Calibration calibration() { return calibration; }
    public Rrf rrf() { return rrf; }
    public Guard guard() { return guard; }
    public Map<String,Object> override() { return override; }

    public void setId(String id) { this.id=id; }
    public void setDesc(String d) { this.desc=d; }
    public void setK(Map<String,Integer> k) { this.k=k; }
    public void setCalibration(Calibration c) { this.calibration=c; }
    public void setRrf(Rrf r) { this.rrf=r; }
    public void setGuard(Guard g) { this.guard=g; }
    public void setOverride(Map<String,Object> o) { this.override=o; }
}