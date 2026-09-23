package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prompt-pose")
public class PromptPoseProperties {

    private boolean enabled = false;
    private Draft draft = new Draft();
    private Policy policy = new Policy();
    private Application application = new Application();
    private Reward reward = new Reward();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Draft getDraft() {
        return draft;
    }

    public void setDraft(Draft draft) {
        this.draft = draft == null ? new Draft() : draft;
    }

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(Policy policy) {
        this.policy = policy == null ? new Policy() : policy;
    }

    public Application getApplication() {
        return application;
    }

    public void setApplication(Application application) {
        this.application = application == null ? new Application() : application;
    }

    public Reward getReward() {
        return reward;
    }

    public void setReward(Reward reward) {
        this.reward = reward == null ? new Reward() : reward;
    }

    public static class Draft {
        private boolean enabled = true;
        private String model = "llmrouter.light";
        private int timeoutMs = 2500;
        private int maxInputChars = 360;
        private int maxOutputChars = 2400;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            this.maxInputChars = maxInputChars;
        }

        public int getMaxOutputChars() {
            return maxOutputChars;
        }

        public void setMaxOutputChars(int maxOutputChars) {
            this.maxOutputChars = maxOutputChars;
        }
    }

    public static class Policy {
        private int maxDraftLines = 6;
        private int maxSelfaskCount = 3;
        private int maxQueryburstCount = 18;
        private int minQueryburstCount = 2;
        private double maxTemperature = 0.55d;
        private boolean allowExternalFree = false;

        public int getMaxDraftLines() {
            return maxDraftLines;
        }

        public void setMaxDraftLines(int maxDraftLines) {
            this.maxDraftLines = maxDraftLines;
        }

        public int getMaxSelfaskCount() {
            return maxSelfaskCount;
        }

        public void setMaxSelfaskCount(int maxSelfaskCount) {
            this.maxSelfaskCount = maxSelfaskCount;
        }

        public int getMaxQueryburstCount() {
            return maxQueryburstCount;
        }

        public void setMaxQueryburstCount(int maxQueryburstCount) {
            this.maxQueryburstCount = maxQueryburstCount;
        }

        public int getMinQueryburstCount() {
            return minQueryburstCount;
        }

        public void setMinQueryburstCount(int minQueryburstCount) {
            this.minQueryburstCount = minQueryburstCount;
        }

        public double getMaxTemperature() {
            return maxTemperature;
        }

        public void setMaxTemperature(double maxTemperature) {
            this.maxTemperature = maxTemperature;
        }

        public boolean isAllowExternalFree() {
            return allowExternalFree;
        }

        public void setAllowExternalFree(boolean allowExternalFree) {
            this.allowExternalFree = allowExternalFree;
        }
    }

    public static class Application {
        private boolean enabled = true;
        private int maxQueryburstCount = 16;
        private int exploreQueryburstCount = 14;
        private int strictQueryburstCount = 8;
        private int failureQueryburstCount = 10;
        private int minCitationsExplore = 2;
        private int minCitationsStrict = 3;
        private int minCitationsFailure = 4;
        private double maxAnswerTemperature = 0.35d;
        private double maxSelfAskTemperature = 0.55d;
        private double lowRewardThreshold = 0.35d;
        private double highRewardThreshold = 0.70d;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxQueryburstCount() {
            return maxQueryburstCount;
        }

        public void setMaxQueryburstCount(int maxQueryburstCount) {
            this.maxQueryburstCount = maxQueryburstCount;
        }

        public int getExploreQueryburstCount() {
            return exploreQueryburstCount;
        }

        public void setExploreQueryburstCount(int exploreQueryburstCount) {
            this.exploreQueryburstCount = exploreQueryburstCount;
        }

        public int getStrictQueryburstCount() {
            return strictQueryburstCount;
        }

        public void setStrictQueryburstCount(int strictQueryburstCount) {
            this.strictQueryburstCount = strictQueryburstCount;
        }

        public int getFailureQueryburstCount() {
            return failureQueryburstCount;
        }

        public void setFailureQueryburstCount(int failureQueryburstCount) {
            this.failureQueryburstCount = failureQueryburstCount;
        }

        public int getMinCitationsExplore() {
            return minCitationsExplore;
        }

        public void setMinCitationsExplore(int minCitationsExplore) {
            this.minCitationsExplore = minCitationsExplore;
        }

        public int getMinCitationsStrict() {
            return minCitationsStrict;
        }

        public void setMinCitationsStrict(int minCitationsStrict) {
            this.minCitationsStrict = minCitationsStrict;
        }

        public int getMinCitationsFailure() {
            return minCitationsFailure;
        }

        public void setMinCitationsFailure(int minCitationsFailure) {
            this.minCitationsFailure = minCitationsFailure;
        }

        public double getMaxAnswerTemperature() {
            return maxAnswerTemperature;
        }

        public void setMaxAnswerTemperature(double maxAnswerTemperature) {
            this.maxAnswerTemperature = maxAnswerTemperature;
        }

        public double getMaxSelfAskTemperature() {
            return maxSelfAskTemperature;
        }

        public void setMaxSelfAskTemperature(double maxSelfAskTemperature) {
            this.maxSelfAskTemperature = maxSelfAskTemperature;
        }

        public double getLowRewardThreshold() {
            return lowRewardThreshold;
        }

        public void setLowRewardThreshold(double lowRewardThreshold) {
            this.lowRewardThreshold = lowRewardThreshold;
        }

        public double getHighRewardThreshold() {
            return highRewardThreshold;
        }

        public void setHighRewardThreshold(double highRewardThreshold) {
            this.highRewardThreshold = highRewardThreshold;
        }
    }

    public static class Reward {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
