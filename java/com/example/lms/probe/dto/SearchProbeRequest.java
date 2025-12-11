package com.example.lms.probe.dto;

public class SearchProbeRequest {
    public String query;
    public Flags flags = new Flags();
    public int webTopK = 10;
    public String intent;

    public static class Flags {
        public boolean useWeb = true;
        public boolean useRag = true;
        public boolean officialSourcesOnly = false;
    }
}