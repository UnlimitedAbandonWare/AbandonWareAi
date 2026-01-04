package com.example.lms.probe.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * SoakProbeResponse
 *
 * - steps: 상태 전이 과정 스냅샷
 */
public class SoakProbeResponse {

    public List<Step> steps = new ArrayList<>();

    public static class Step {
        public String name;
        public Object state;   // NightmareBreaker.StateView or simple map
        public String note;

        public Step() {}
        public Step(String name, Object state, String note) {
            this.name = name;
            this.state = state;
            this.note = note;
        }
    }
}
