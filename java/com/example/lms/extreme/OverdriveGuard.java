
package com.example.lms.extreme;


public class OverdriveGuard {
    public boolean shouldOverdrive(int candidates, double risk) {
        return candidates < 3 || risk > 0.7;
    }
}