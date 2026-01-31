package com.abandonwareai.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demo-1 idle-train settings.
 *
 * <p>IMPORTANT: do not gate bean creation with @ConditionalOnProperty here.
 * Prefix mismatches easily create silent failures ("enabled but not running").
 *
 * Runtime code should check {@link #isEnabled()}.
 */
@ConfigurationProperties(prefix = "nova.idle-train")
public class IdleTrainProperties {
    private boolean enabled = true;
    private boolean autoTrainEnabled = true;
    private int minQaAcceptedToTrain = 10;
    private int maxTrainRunsPerDay = 2;
    private String trainRagJsonlPath = "data/train/train_rag.jsonl";
    private String trainRagDatasetName = "autolearn-train";
    private String vectorIndexPath = "data/vector/index.jsonl";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAutoTrainEnabled() { return autoTrainEnabled; }
    public void setAutoTrainEnabled(boolean autoTrainEnabled) { this.autoTrainEnabled = autoTrainEnabled; }

    public int getMinQaAcceptedToTrain() { return minQaAcceptedToTrain; }
    public void setMinQaAcceptedToTrain(int minQaAcceptedToTrain) { this.minQaAcceptedToTrain = minQaAcceptedToTrain; }

    public int getMaxTrainRunsPerDay() { return maxTrainRunsPerDay; }
    public void setMaxTrainRunsPerDay(int maxTrainRunsPerDay) { this.maxTrainRunsPerDay = maxTrainRunsPerDay; }

    public String getTrainRagJsonlPath() { return trainRagJsonlPath; }
    public void setTrainRagJsonlPath(String trainRagJsonlPath) { this.trainRagJsonlPath = trainRagJsonlPath; }

    public String getTrainRagDatasetName() { return trainRagDatasetName; }
    public void setTrainRagDatasetName(String trainRagDatasetName) { this.trainRagDatasetName = trainRagDatasetName; }

    public String getVectorIndexPath() { return vectorIndexPath; }
    public void setVectorIndexPath(String vectorIndexPath) { this.vectorIndexPath = vectorIndexPath; }
}
