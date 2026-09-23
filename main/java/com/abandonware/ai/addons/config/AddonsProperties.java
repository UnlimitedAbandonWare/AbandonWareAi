package com.abandonware.ai.addons.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.Map;




@ConfigurationProperties(prefix = "addons")
public class AddonsProperties {

    private final Complexity complexity = new Complexity();
    private final Budget budget = new Budget();
    private final Onnx onnx = new Onnx();
    private final Web web = new Web();
    private final Vector vector = new Vector();
    private final Synthesis synthesis = new Synthesis();
    private final Ocr ocr = new Ocr();

    public Complexity getComplexity() { return complexity; }
    public Budget getBudget() { return budget; }
    public Onnx getOnnx() { return onnx; }
    public Web getWeb() { return web; }
    public Vector getVector() { return vector; }
    public Synthesis getSynthesis() { return synthesis; }
    public Ocr getOcr() { return ocr; }

    public static class Complexity {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
    public static class Budget {
        private long defaultMs = 1_500;
        public long getDefaultMs() { return defaultMs; }
        public void setDefaultMs(long defaultMs) { this.defaultMs = Math.max(1L, defaultMs); }
    }
    public static class Onnx {
        private int maxConcurrent = 4;
        private long queueWaitMs = 120;
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = Math.max(1, maxConcurrent); }
        public long getQueueWaitMs() { return queueWaitMs; }
        public void setQueueWaitMs(long queueWaitMs) { this.queueWaitMs = Math.max(1L, queueWaitMs); }
    }
    public static class Web {
        private boolean enabled = true;
        private int topKDefault = 5;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTopKDefault() { return topKDefault; }
        public void setTopKDefault(int topKDefault) { this.topKDefault = Math.max(0, topKDefault); }
    }
    public static class Vector {
        private int topKDefault = 12;
        public int getTopKDefault() { return topKDefault; }
        public void setTopKDefault(int topKDefault) { this.topKDefault = Math.max(0, topKDefault); }
    }
    public static class Synthesis {
        private double moeMix = 0.5;
        private int minBytesPerItem = 240;
        private Map<String, Double> authorityTier = new HashMap<>();
        public double getMoeMix() { return moeMix; }
        public void setMoeMix(double moeMix) { this.moeMix = probability(moeMix); }
        public int getMinBytesPerItem() { return minBytesPerItem; }
        public void setMinBytesPerItem(int minBytesPerItem) { this.minBytesPerItem = Math.max(1, minBytesPerItem); }
        public Map<String, Double> getAuthorityTier() { return authorityTier; }
        public void setAuthorityTier(Map<String, Double> authorityTier) {
            this.authorityTier = authorityTier == null ? new HashMap<>() : new HashMap<>(authorityTier);
        }
        private static double probability(double value) {
            if (!Double.isFinite(value)) return 0.0d;
            return Math.max(0.0d, Math.min(1.0d, value));
        }
    }
    public static class Ocr {
        private boolean enabled = false;
        private int topK = 6;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = Math.max(0, topK); }
    }
}
