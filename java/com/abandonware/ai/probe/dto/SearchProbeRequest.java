package com.abandonware.ai.probe.dto;

public class SearchProbeRequest {
  public boolean useWeb = true;
  public boolean useRag = true;
  public boolean officialSourcesOnly = false;
  public Integer webTopK;
  public String intent;
  public String query;
}