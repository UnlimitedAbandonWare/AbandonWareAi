package com.abandonware.ai.example.lms.cfvm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;



@Data
@ConfigurationProperties(prefix = "cfvm.raw")
public class CfvmRawProperties {
    private boolean enabled = true;
    private int maxSlots = 100;
    private double temperature = 0.8;
    private Reorder reorder = new Reorder();

    @Data
    public static class Reorder {
        private boolean enabled = true;
    }
}