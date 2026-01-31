package com.example.lms.service.soak.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "soak.quick-runner")
public class SoakQuickRunnerProperties {

    private boolean enabled = false;
    private boolean onStartup = false;
    private boolean scheduled = false;

    /**
     * CLI mode:
     * - run once on startup and (optionally) exit with exitCode
     */
    private boolean cli = false;
    private boolean exitAfterRun = true;

    private String topic = "naver-brave-fixed10";
    private int k = 10;
    private String outputPath = "artifacts/soak/quick_report.json";

    /** Provider split: the runner executes each provider in order */
    private List<String> providers = Arrays.asList("NAVER", "BRAVE");

    /** Schedule CRON (only used when scheduled=true) */
    private String cron = "0 30 4 * * *";

    private Gate gate = new Gate();

    public static class Gate {
        private double warnEvidenceMin = 0.75;
        private double failEvidenceMin = 0.60;
        private double warnHitMin = 0.85;
        private double failHitMin = 0.60;
        private int warnExitCode = 0;
        private int failExitCode = 2;

        public double getWarnEvidenceMin() {
            return warnEvidenceMin;
        }

        public void setWarnEvidenceMin(double warnEvidenceMin) {
            this.warnEvidenceMin = warnEvidenceMin;
        }

        public double getFailEvidenceMin() {
            return failEvidenceMin;
        }

        public void setFailEvidenceMin(double failEvidenceMin) {
            this.failEvidenceMin = failEvidenceMin;
        }

        public double getWarnHitMin() {
            return warnHitMin;
        }

        public void setWarnHitMin(double warnHitMin) {
            this.warnHitMin = warnHitMin;
        }

        public double getFailHitMin() {
            return failHitMin;
        }

        public void setFailHitMin(double failHitMin) {
            this.failHitMin = failHitMin;
        }

        public int getWarnExitCode() {
            return warnExitCode;
        }

        public void setWarnExitCode(int warnExitCode) {
            this.warnExitCode = warnExitCode;
        }

        public int getFailExitCode() {
            return failExitCode;
        }

        public void setFailExitCode(int failExitCode) {
            this.failExitCode = failExitCode;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isOnStartup() {
        return onStartup;
    }

    public void setOnStartup(boolean onStartup) {
        this.onStartup = onStartup;
    }

    public boolean isScheduled() {
        return scheduled;
    }

    public void setScheduled(boolean scheduled) {
        this.scheduled = scheduled;
    }

    public boolean isCli() {
        return cli;
    }

    public void setCli(boolean cli) {
        this.cli = cli;
    }

    public boolean isExitAfterRun() {
        return exitAfterRun;
    }

    public void setExitAfterRun(boolean exitAfterRun) {
        this.exitAfterRun = exitAfterRun;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getK() {
        return k;
    }

    public void setK(int k) {
        this.k = k;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public List<String> getProviders() {
        return providers;
    }

    public void setProviders(List<String> providers) {
        this.providers = providers;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public Gate getGate() {
        return gate;
    }

    public void setGate(Gate gate) {
        this.gate = gate;
    }
}
