package com.abandonwareai.overdrive;

import org.springframework.stereotype.Component;

@Component
public class OverdriveGuard {
    public boolean shouldActivate(double scarcity, double conflict){ return scarcity>0.3 || conflict>0.4; }

}