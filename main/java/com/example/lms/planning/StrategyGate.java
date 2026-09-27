
package com.example.lms.planning;

import com.example.lms.planning.artplate.ArtPlate;



public interface StrategyGate {
    ArtPlate pick(ComplexityScore score, StrategyTelemetry telemetry);
}