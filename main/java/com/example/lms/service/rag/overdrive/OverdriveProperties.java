package com.example.lms.service.rag.overdrive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.overdrive")
public class OverdriveProperties {
    private boolean enabled = true;
    private Trigger trigger = new Trigger();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public void setTrigger(Trigger trigger) {
        this.trigger = trigger == null ? new Trigger() : trigger;
    }

    public int getMinPool() {
        return trigger.getMinPool();
    }

    public double getMinAuthority() {
        return trigger.getMinAuthority();
    }

    public double getContradictionTh() {
        return trigger.getContradictionTh();
    }

    public double getScoreTh() {
        return trigger.getScoreTh();
    }

    public double getErrorRateTh() {
        return trigger.getErrorRateTh();
    }

    public double getErrorWeight() {
        return trigger.getErrorWeight();
    }

    public double getStarvationTh() {
        return trigger.getStarvationTh();
    }

    public double getStarvationWeight() {
        return trigger.getStarvationWeight();
    }

    public double getRetrievalFailureWeight() {
        return trigger.getRetrievalFailureWeight();
    }

    public static class Trigger {
        private int minPool = 4;
        private double minAuthority = 0.55d;
        private double contradictionTh = 0.60d;
        private double scoreTh = 0.55d;
        private double errorRateTh = 0.35d;
        private double errorWeight = 0.10d;
        private double starvationTh = 0.50d;
        private double starvationWeight = 0.08d;
        private double retrievalFailureWeight = 0.08d;

        public int getMinPool() {
            return minPool;
        }

        public void setMinPool(int minPool) {
            this.minPool = Math.max(1, minPool);
        }

        public double getMinAuthority() {
            return minAuthority;
        }

        public void setMinAuthority(double minAuthority) {
            this.minAuthority = minAuthority;
        }

        public double getContradictionTh() {
            return contradictionTh;
        }

        public void setContradictionTh(double contradictionTh) {
            this.contradictionTh = contradictionTh;
        }

        public double getScoreTh() {
            return scoreTh;
        }

        public void setScoreTh(double scoreTh) {
            this.scoreTh = scoreTh;
        }

        public double getErrorRateTh() {
            return errorRateTh;
        }

        public void setErrorRateTh(double errorRateTh) {
            this.errorRateTh = errorRateTh;
        }

        public double getErrorWeight() {
            return errorWeight;
        }

        public void setErrorWeight(double errorWeight) {
            this.errorWeight = errorWeight;
        }

        public double getStarvationTh() {
            return starvationTh;
        }

        public void setStarvationTh(double starvationTh) {
            this.starvationTh = starvationTh;
        }

        public double getStarvationWeight() {
            return starvationWeight;
        }

        public void setStarvationWeight(double starvationWeight) {
            this.starvationWeight = starvationWeight;
        }

        public double getRetrievalFailureWeight() {
            return retrievalFailureWeight;
        }

        public void setRetrievalFailureWeight(double retrievalFailureWeight) {
            this.retrievalFailureWeight = retrievalFailureWeight;
        }
    }
}
