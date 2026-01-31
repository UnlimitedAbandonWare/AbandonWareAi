
package com.example.lms.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "autolearn")
public class AutoLearnProperties {
    private boolean enabled = false;
    private boolean localOnly = true;
    private Idle idle = new Idle();
    private Schedule schedule = new Schedule();
    private Dataset dataset = new Dataset();
    private Suggestions suggestions = new Suggestions();

    public static class Idle {
        private double cpuThreshold = 0.20;
        private int quietSeconds = 60;
        public double getCpuThreshold() { return cpuThreshold; }
        public void setCpuThreshold(double cpuThreshold) { this.cpuThreshold = cpuThreshold; }
        public int getQuietSeconds() { return quietSeconds; }
        public void setQuietSeconds(int quietSeconds) { this.quietSeconds = quietSeconds; }
    }
    public static class Schedule {
        private long fixedDelayMs = 300_000L;
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long v) { this.fixedDelayMs = v; }
    }
    public static class Dataset {
        private String path = "data/train_rag.jsonl";
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
    public static class Suggestions {
        private String path = "data/tuning.suggestions.yaml";
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isLocalOnly() { return localOnly; }
    public void setLocalOnly(boolean localOnly) { this.localOnly = localOnly; }
    public Idle getIdle() { return idle; }
    public void setIdle(Idle idle) { this.idle = idle; }
    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule; }
    public Dataset getDataset() { return dataset; }
    public void setDataset(Dataset dataset) { this.dataset = dataset; }
    public Suggestions getSuggestions() { return suggestions; }
    public void setSuggestions(Suggestions suggestions) { this.suggestions = suggestions; }
}
