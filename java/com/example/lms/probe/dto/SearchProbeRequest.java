package com.example.lms.probe.dto;

public class SearchProbeRequest {
    public String query;
    /** web | vector | candidates */
    public String seedMode = "candidates";
    /** seed 후보(재현용) */
    public java.util.List<CandidateDTO> seed;
    public Flags flags = new Flags();
    public int webTopK = 10;
    public String intent;

    public static class Flags {
        public boolean useWeb = true;
        public boolean useRag = true;
        public boolean officialSourcesOnly = false;
        public boolean seedOnly = false;
    }
}